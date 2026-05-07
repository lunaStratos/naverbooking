# -*- mode: python ; coding: utf-8 -*-
import os
from PyInstaller.utils.hooks import collect_all

# Playwright 브라우저 캐시 경로 — 환경에 맞게 수정 필요.
# 기본 위치: %USERPROFILE%\AppData\Local\ms-playwright\
_PW_CACHE = os.path.join(os.environ.get("USERPROFILE", ""), "AppData", "Local", "ms-playwright")

datas = [
    (os.path.join(_PW_CACHE, "chromium_headless_shell-1208"), "ms-playwright/chromium_headless_shell-1208"),
    (os.path.join(_PW_CACHE, "ffmpeg-1011"),                  "ms-playwright/ffmpeg-1011"),
]
binaries = []
hiddenimports = []
tmp_ret = collect_all('playwright')
datas += tmp_ret[0]; binaries += tmp_ret[1]; hiddenimports += tmp_ret[2]


a = Analysis(
    ['index.py'],
    pathex=[],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='NaverReservationCheck',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=['naver.ico'],
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name='NaverReservationCheck',
)
