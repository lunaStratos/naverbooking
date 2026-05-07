import asyncio
import json
import os
import queue
import smtplib
import sys
import threading
import tkinter as tk
from datetime import datetime
from email.message import EmailMessage
from tkinter import ttk, scrolledtext, messagebox

if getattr(sys, "frozen", False):
    _bundled_browsers = os.path.join(getattr(sys, "_MEIPASS", ""), "ms-playwright")
    if os.path.isdir(_bundled_browsers):
        os.environ.setdefault("PLAYWRIGHT_BROWSERS_PATH", _bundled_browsers)

from playwright.async_api import async_playwright

DEFAULT_PLACE_ID = "1610165006"
DEFAULT_INTERVAL = 60
DEFAULT_MONTHS_TO_CHECK = 4
USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/127.0.0.0 Safari/537.36"
)
CONFIG_PATH = os.path.expanduser("~/.naver_reservation_check.json")

DEFAULT_CONFIG = {
    "place_id": DEFAULT_PLACE_ID,
    "interval": DEFAULT_INTERVAL,
    "months_to_check": DEFAULT_MONTHS_TO_CHECK,
    "email_enabled": False,
    "smtp_host": "smtp.naver.com",
    "smtp_port": 465,
    "smtp_ssl": True,
    "smtp_user": "",
    "smtp_password": "",
    "mail_from": "",
    "mail_to": "",
}


def load_config():
    cfg = dict(DEFAULT_CONFIG)
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            cfg.update(json.load(f))
    except (FileNotFoundError, json.JSONDecodeError):
        pass
    return cfg


def save_config(cfg):
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    try:
        os.chmod(CONFIG_PATH, 0o600)
    except OSError:
        pass


def send_mail(cfg, subject, body):
    msg = EmailMessage()
    msg["From"] = cfg.get("mail_from") or cfg.get("smtp_user", "")
    msg["To"] = cfg["mail_to"]
    msg["Subject"] = subject
    msg.set_content(body)
    host = cfg["smtp_host"]
    port = int(cfg["smtp_port"])
    user = cfg.get("smtp_user", "")
    pw = cfg.get("smtp_password", "")
    if cfg.get("smtp_ssl"):
        with smtplib.SMTP_SSL(host, port, timeout=20) as s:
            if user:
                s.login(user, pw)
            s.send_message(msg)
    else:
        with smtplib.SMTP(host, port, timeout=20) as s:
            try:
                s.starttls()
            except smtplib.SMTPException:
                pass
            if user:
                s.login(user, pw)
            s.send_message(msg)


def ticket_url(place_id):
    return f"https://pcmap.place.naver.com/place/{place_id}/ticket"


async def discover_booking_items(page, place_id):
    await page.goto(ticket_url(place_id), wait_until="domcontentloaded")
    await asyncio.sleep(3)

    links = page.locator('a[href*="m.booking.naver.com"]')
    count = await links.count()
    items = []
    seen = set()
    for i in range(count):
        href = await links.nth(i).get_attribute("href")
        if not href or href in seen:
            continue
        seen.add(href)
        title = ""
        try:
            card = links.nth(i).locator("xpath=ancestor::li[1]")
            if await card.count() > 0:
                title = (await card.first.inner_text()).strip().splitlines()[0].strip()
        except Exception:
            pass
        if not title:
            title = href.split("/items/")[-1].split("?")[0]
        items.append((title, href))
    return items


async def read_month(page):
    title = ""
    if await page.locator(".calendar_title").count() > 0:
        title = (await page.locator(".calendar_title").first.inner_text()).strip()

    date_cells = page.locator(".calendar_date")
    count = await date_cells.count()
    days = []
    for i in range(count):
        cell = date_cells.nth(i)
        class_attr = (await cell.get_attribute("class")) or ""
        try:
            text = (await cell.inner_text()).strip().splitlines()[0].strip()
        except Exception:
            text = ""
        classes = class_attr.split()
        available = (
            "unselectable" not in classes
            and "dayoff" not in classes
            and "closed" not in classes
            and "today" not in classes
        )
        days.append((text, available))
    return title, days


async def click_next(page):
    btn = page.locator(".btn_next").first
    if await btn.count() == 0:
        return False
    try:
        await btn.click()
        await asyncio.sleep(1.2)
        return True
    except Exception:
        return False


async def check_item(page, title, url, log, months):
    log(f"\n▶ 상품: {title}")
    log(f"  URL: {url}")
    await page.goto(url, wait_until="domcontentloaded")

    try:
        await page.wait_for_selector(".calendar_date", timeout=15000)
    except Exception:
        log("  ! 달력을 찾지 못했습니다.")
        return None

    result = {}
    for month_idx in range(months):
        month_title, days = await read_month(page)
        label = month_title if month_title else f"(달 +{month_idx})"
        available_days = [d for d, ok in days if ok and d]
        unavailable_days = [d for d, ok in days if not ok and d]
        result[label] = available_days
        log(f"  [{label}]")
        log(f"    예약 가능 ({len(available_days)}): "
            f"{', '.join(available_days) if available_days else '없음'}")
        log(f"    예약 불가 ({len(unavailable_days)}): "
            f"{', '.join(unavailable_days) if unavailable_days else '없음'}")

        if month_idx < months - 1:
            if not await click_next(page):
                log("  ! 다음 달 버튼을 누르지 못했습니다.")
                break
    return result


class EmailSettingsDialog(tk.Toplevel):
    def __init__(self, parent, cfg, on_save):
        super().__init__(parent)
        self.title("메일 알림 설정")
        self.cfg = cfg
        self.on_save = on_save
        self.transient(parent)
        self.grab_set()
        self.resizable(False, False)

        frame = ttk.Frame(self, padding=12)
        frame.pack()

        rows = [
            ("smtp_host", "SMTP 호스트"),
            ("smtp_port", "SMTP 포트"),
            ("smtp_user", "사용자(이메일)"),
            ("smtp_password", "비밀번호"),
            ("mail_from", "From (선택)"),
            ("mail_to", "받는 사람"),
        ]
        self.vars = {}
        for i, (key, label) in enumerate(rows):
            ttk.Label(frame, text=label).grid(row=i, column=0, sticky="e", padx=4, pady=2)
            v = tk.StringVar(value=str(cfg.get(key, "")))
            self.vars[key] = v
            entry = ttk.Entry(
                frame, textvariable=v, width=34,
                show="*" if key == "smtp_password" else "",
            )
            entry.grid(row=i, column=1, padx=4, pady=2)

        self.ssl_var = tk.BooleanVar(value=bool(cfg.get("smtp_ssl", True)))
        ttk.Checkbutton(
            frame,
            text="SSL 사용 (포트 465 권장 / 587이면 해제 → STARTTLS)",
            variable=self.ssl_var,
        ).grid(row=len(rows), column=0, columnspan=2, sticky="w", pady=(6, 4))

        hint = ttk.Label(
            frame,
            text="* 네이버: smtp.naver.com:465 SSL, 앱 비밀번호 필요\n"
                 "* Gmail: smtp.gmail.com:465 SSL, 앱 비밀번호 필요",
            foreground="#666",
        )
        hint.grid(row=len(rows) + 1, column=0, columnspan=2, sticky="w", pady=(0, 6))

        btn_frame = ttk.Frame(frame)
        btn_frame.grid(row=len(rows) + 2, column=0, columnspan=2, pady=(8, 0))
        ttk.Button(btn_frame, text="테스트 발송", command=self.on_test).pack(side=tk.LEFT, padx=4)
        ttk.Button(btn_frame, text="저장", command=self.on_save_click).pack(side=tk.LEFT, padx=4)
        ttk.Button(btn_frame, text="취소", command=self.destroy).pack(side=tk.LEFT, padx=4)

    def collect(self):
        out = dict(self.cfg)
        for k, v in self.vars.items():
            out[k] = v.get().strip()
        out["smtp_ssl"] = self.ssl_var.get()
        return out

    def on_test(self):
        try:
            cfg = self.collect()
            send_mail(
                cfg,
                "[네이버 예약 체크] 테스트 메일",
                "테스트 메일입니다.\n설정이 정상이라면 이 메일이 수신됩니다.",
            )
            messagebox.showinfo("테스트 발송", "테스트 메일을 보냈습니다.", parent=self)
        except Exception as e:
            messagebox.showerror("테스트 실패", str(e), parent=self)

    def on_save_click(self):
        self.on_save(self.collect())
        self.destroy()


class App:
    def __init__(self, root):
        self.root = root
        root.title("네이버 예약 체크")
        root.geometry("980x640")

        self.config = load_config()
        self.log_queue = queue.Queue()
        self.items = []
        self.item_vars = []
        self.worker_thread = None
        self.stop_event = threading.Event()
        self.last_sent_keys = None

        self._build_ui()
        self._poll_log()
        root.protocol("WM_DELETE_WINDOW", self._on_close)

    def _build_ui(self):
        top = ttk.Frame(self.root, padding=8)
        top.pack(side=tk.TOP, fill=tk.X)

        ttk.Label(top, text="Place ID:").pack(side=tk.LEFT)
        self.place_id_var = tk.StringVar(value=self.config.get("place_id", DEFAULT_PLACE_ID))
        ttk.Entry(top, textvariable=self.place_id_var, width=14).pack(side=tk.LEFT, padx=(2, 10))

        ttk.Label(top, text="주기:").pack(side=tk.LEFT)
        total_sec = int(self.config.get("interval", DEFAULT_INTERVAL) or DEFAULT_INTERVAL)
        hh, rem = divmod(total_sec, 3600)
        mm, ss = divmod(rem, 60)
        self.interval_h_var = tk.StringVar(value=str(hh))
        self.interval_m_var = tk.StringVar(value=str(mm))
        self.interval_s_var = tk.StringVar(value=str(ss))
        ttk.Entry(top, textvariable=self.interval_h_var, width=4).pack(side=tk.LEFT, padx=(2, 0))
        ttk.Label(top, text="시").pack(side=tk.LEFT, padx=(2, 4))
        ttk.Entry(top, textvariable=self.interval_m_var, width=4).pack(side=tk.LEFT)
        ttk.Label(top, text="분").pack(side=tk.LEFT, padx=(2, 4))
        ttk.Entry(top, textvariable=self.interval_s_var, width=4).pack(side=tk.LEFT)
        ttk.Label(top, text="초").pack(side=tk.LEFT, padx=(2, 10))

        ttk.Label(top, text="체크 개월:").pack(side=tk.LEFT)
        self.months_var = tk.StringVar(
            value=str(self.config.get("months_to_check", DEFAULT_MONTHS_TO_CHECK))
        )
        ttk.Entry(top, textvariable=self.months_var, width=4).pack(side=tk.LEFT, padx=(2, 10))

        self.refresh_btn = ttk.Button(top, text="상품 새로고침", command=self.on_refresh)
        self.refresh_btn.pack(side=tk.LEFT, padx=2)
        self.start_btn = ttk.Button(top, text="시작", command=self.on_start, state=tk.DISABLED)
        self.start_btn.pack(side=tk.LEFT, padx=2)
        self.stop_btn = ttk.Button(top, text="중지", command=self.on_stop, state=tk.DISABLED)
        self.stop_btn.pack(side=tk.LEFT, padx=2)
        ttk.Button(top, text="모두선택", command=lambda: self._set_all(True)).pack(side=tk.LEFT, padx=2)
        ttk.Button(top, text="모두해제", command=lambda: self._set_all(False)).pack(side=tk.LEFT, padx=2)

        ttk.Separator(top, orient=tk.VERTICAL).pack(side=tk.LEFT, fill=tk.Y, padx=8)
        ttk.Button(top, text="메일 설정", command=self.on_mail_settings).pack(side=tk.LEFT, padx=2)
        self.email_enabled_var = tk.BooleanVar(value=bool(self.config.get("email_enabled", False)))
        ttk.Checkbutton(
            top, text="메일 알림", variable=self.email_enabled_var,
            command=self._on_email_toggle,
        ).pack(side=tk.LEFT, padx=2)

        self.status_var = tk.StringVar(value="대기 중")
        ttk.Label(top, textvariable=self.status_var).pack(side=tk.RIGHT)

        body = ttk.PanedWindow(self.root, orient=tk.HORIZONTAL)
        body.pack(fill=tk.BOTH, expand=True, padx=8, pady=(0, 8))

        left = ttk.LabelFrame(body, text="상품 선택", padding=4)
        body.add(left, weight=1)

        self.items_canvas = tk.Canvas(left, highlightthickness=0)
        items_sb = ttk.Scrollbar(left, orient=tk.VERTICAL, command=self.items_canvas.yview)
        self.items_frame = ttk.Frame(self.items_canvas)
        self.items_frame.bind(
            "<Configure>",
            lambda e: self.items_canvas.configure(scrollregion=self.items_canvas.bbox("all")),
        )
        self._items_window = self.items_canvas.create_window(
            (0, 0), window=self.items_frame, anchor="nw"
        )
        self.items_canvas.bind(
            "<Configure>",
            lambda e: self.items_canvas.itemconfigure(self._items_window, width=e.width),
        )
        self.items_canvas.configure(yscrollcommand=items_sb.set)
        self.items_canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        items_sb.pack(side=tk.RIGHT, fill=tk.Y)

        right = ttk.LabelFrame(body, text="로그", padding=4)
        body.add(right, weight=3)
        self.log_text = scrolledtext.ScrolledText(right, wrap=tk.WORD, state=tk.DISABLED)
        self.log_text.pack(fill=tk.BOTH, expand=True)

    def _on_email_toggle(self):
        self.config["email_enabled"] = self.email_enabled_var.get()
        save_config(self.config)

    def on_mail_settings(self):
        def on_save(new_cfg):
            self.config.update(new_cfg)
            save_config(self.config)
            self.log("메일 설정을 저장했습니다.")
        EmailSettingsDialog(self.root, self.config, on_save)

    def _interval_seconds(self):
        h = int(self.interval_h_var.get() or 0)
        m = int(self.interval_m_var.get() or 0)
        s = int(self.interval_s_var.get() or 0)
        return h * 3600 + m * 60 + s

    def _on_close(self):
        try:
            self.config["place_id"] = self.place_id_var.get().strip()
            self.config["interval"] = self._interval_seconds() or DEFAULT_INTERVAL
            self.config["email_enabled"] = self.email_enabled_var.get()
            save_config(self.config)
        except Exception:
            pass
        self.stop_event.set()
        self.root.destroy()

    def log(self, msg):
        self.log_queue.put(msg)

    def _poll_log(self):
        try:
            while True:
                msg = self.log_queue.get_nowait()
                self.log_text.configure(state=tk.NORMAL)
                self.log_text.insert(tk.END, msg + "\n")
                self.log_text.see(tk.END)
                self.log_text.configure(state=tk.DISABLED)
        except queue.Empty:
            pass
        self.root.after(100, self._poll_log)

    def _set_status(self, s):
        self.root.after(0, lambda: self.status_var.set(s))

    def _set_all(self, value):
        for v in self.item_vars:
            v.set(value)

    def _populate_items(self, items):
        for child in self.items_frame.winfo_children():
            child.destroy()
        self.items = items
        self.item_vars = []
        for title, _url in items:
            var = tk.BooleanVar(value=True)
            self.item_vars.append(var)
            ttk.Checkbutton(self.items_frame, text=title, variable=var).pack(
                anchor="w", padx=4, pady=1
            )
        self.start_btn.configure(state=tk.NORMAL if items else tk.DISABLED)

    def on_refresh(self):
        if self.worker_thread and self.worker_thread.is_alive():
            self.log("! 작업 중에는 새로고침할 수 없습니다.")
            return
        self.refresh_btn.configure(state=tk.DISABLED)
        self.start_btn.configure(state=tk.DISABLED)
        self._set_status("상품 목록 가져오는 중...")
        threading.Thread(target=self._refresh_thread, daemon=True).start()

    def _refresh_thread(self):
        try:
            items = asyncio.run(self._discover())
            self.root.after(0, lambda: self._populate_items(items))
            self.log(f"상품 {len(items)}개 발견")
        except Exception as e:
            err = str(e)
            self.log(f"! 상품 목록 가져오기 실패: {err}")
        finally:
            self.root.after(0, lambda: self.refresh_btn.configure(state=tk.NORMAL))
            self._set_status("대기 중")

    async def _discover(self):
        place_id = self.place_id_var.get().strip()
        async with async_playwright() as p:
            browser = await p.chromium.launch(headless=True)
            try:
                context = await browser.new_context(locale="ko-KR", user_agent=USER_AGENT)
                page = await context.new_page()
                items = await discover_booking_items(page, place_id)
                await context.close()
                return items
            finally:
                await browser.close()

    def on_start(self):
        if self.worker_thread and self.worker_thread.is_alive():
            return
        if not self.items:
            self.log("! 먼저 '상품 새로고침'을 눌러주세요.")
            return
        selected = [(t, u) for (t, u), v in zip(self.items, self.item_vars) if v.get()]
        if not selected:
            self.log("! 체크할 상품을 하나 이상 선택해주세요.")
            return
        try:
            interval = self._interval_seconds()
            if interval < 1:
                raise ValueError
        except ValueError:
            self.log("! 주기를 확인해주세요. (시/분/초는 정수, 합이 1초 이상)")
            return
        try:
            months = int(self.months_var.get())
            if months < 1:
                raise ValueError
        except ValueError:
            self.log("! 체크 개월 수는 1 이상의 정수여야 합니다.")
            return
        self.config["place_id"] = self.place_id_var.get().strip()
        self.config["interval"] = interval
        self.config["months_to_check"] = months
        save_config(self.config)
        self.last_sent_keys = None
        self.stop_event.clear()
        self.start_btn.configure(state=tk.DISABLED)
        self.refresh_btn.configure(state=tk.DISABLED)
        self.stop_btn.configure(state=tk.NORMAL)
        self.worker_thread = threading.Thread(
            target=self._loop_thread, args=(selected, interval, months), daemon=True
        )
        self.worker_thread.start()

    def on_stop(self):
        self.stop_event.set()
        self._set_status("중지 중...")

    def _loop_thread(self, selected, interval, months):
        try:
            asyncio.run(self._run_loop(selected, interval, months))
        except Exception as e:
            err = str(e)
            self.log(f"! 오류: {err}")
        finally:
            self.root.after(0, lambda: self.start_btn.configure(state=tk.NORMAL))
            self.root.after(0, lambda: self.refresh_btn.configure(state=tk.NORMAL))
            self.root.after(0, lambda: self.stop_btn.configure(state=tk.DISABLED))
            self._set_status("대기 중")

    async def _run_loop(self, selected, interval, months):
        async with async_playwright() as p:
            browser = await p.chromium.launch(headless=True)
            try:
                context = await browser.new_context(locale="ko-KR", user_agent=USER_AGENT)
                page = await context.new_page()
                while not self.stop_event.is_set():
                    self._set_status("체크 중...")
                    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                    self.log(f"\n========== [{now}] 예약 가능 여부 확인 ==========")
                    available_items = []
                    current_keys = set()
                    for title, url in selected:
                        if self.stop_event.is_set():
                            break
                        try:
                            result = await check_item(page, title, url, self.log, months)
                        except Exception as e:
                            self.log(f"  ! 상품 체크 중 오류: {e}")
                            continue
                        if result is None:
                            continue
                        item_avail = {m: ds for m, ds in result.items() if ds}
                        if item_avail:
                            available_items.append((title, url, item_avail))
                            for month, days in item_avail.items():
                                for d in days:
                                    current_keys.add(f"{title}::{month}::{d}")
                    if self.config.get("email_enabled"):
                        if not current_keys:
                            self.last_sent_keys = None
                        elif current_keys != self.last_sent_keys:
                            self._send_availability_email(available_items)
                            self.last_sent_keys = frozenset(current_keys)
                    if self.stop_event.is_set():
                        break
                    self._set_status(f"{interval}초 대기 중...")
                    self.log(f"\n-- {interval}초 후 재확인 --")
                    for _ in range(interval):
                        if self.stop_event.is_set():
                            break
                        await asyncio.sleep(1)
            finally:
                await browser.close()

    def _send_availability_email(self, available_items):
        cfg = self.config
        if not cfg.get("mail_to") or not cfg.get("smtp_host"):
            self.log("! 메일 설정이 비어 있어 알림을 보낼 수 없습니다.")
            return
        subject = "[네이버 예약] 예약 가능 알림"
        lines = [f"확인 시각: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}", ""]
        for title, url, months in available_items:
            lines.append(f"▶ {title}")
            lines.append(f"  URL: {url}")
            for month, days in months.items():
                lines.append(f"  [{month}] {', '.join(days)}")
            lines.append("")
        body = "\n".join(lines)
        try:
            send_mail(cfg, subject, body)
            self.log(f"✉ 알림 메일 전송 완료 ({cfg['mail_to']})")
        except Exception as e:
            self.log(f"! 메일 전송 실패: {e}")


def main():
    root = tk.Tk()
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
