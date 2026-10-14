#!/usr/bin/env python3
"""Ребрендинг строк Nandagram Goloka Messenger.
1) values-ru/strings.xml собирается из официального экспорта translations.telegram.org (ru_android_export.xml)
   для всех ключей базового values/strings.xml; ключи, которых нет в экспорте, берутся из старого values-ru.
2) Во всех values*/strings.xml пользовательское «Telegram» -> «Nandagram Goloka Messenger»
   (URL, домены, @-имена, идентификаторы вида TelegramX не трогаются).
3) AppName / AppNameBeta = Nandagram (подпись на рабочем столе).
Запуск: python3 scripts/nandagram/branding.py  (из корня репозитория, идемпотентно)"""
import re, os, glob
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "TMessagesProj/src/main/res")
NEW = "Nandagram Goloka Messenger"
LAUNCHER = "Nandagram"
STR = re.compile(r'(<string\s+name="([^"]+)"[^>]*>)(.*?)(</string>)', re.S)
def brand(v):
    v = re.sub(r'(?:(?<=\\n)|(?<![A-Za-z0-9@/.:-]))TELEGRAM(?![A-Za-z0-9_])', NEW.upper(), v)
    return re.sub(r'(?:(?<=\\n)|(?<![A-Za-z0-9@/.:-]))Telegram(?![\w.]*\.(?:org|me|com))(?![A-Za-z0-9_])', NEW, v)
def entries(path):
    return {m.group(2): m.group(0) for m in STR.finditer(open(path, encoding="utf-8").read())}
base = open(os.path.join(RES, "values/strings.xml"), encoding="utf-8").read()
base_keys = [m.group(2) for m in STR.finditer(base)]
exp = entries(os.path.join(os.path.dirname(__file__), "ru_android_export.xml"))
ru_path = os.path.join(RES, "values-ru/strings.xml")
old_ru = entries(ru_path) if os.path.exists(ru_path) else {}
lines = []
for k in base_keys:
    e = exp.get(k) or old_ru.get(k)
    if e: lines.append("    " + e)
open(ru_path, "w", encoding="utf-8").write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + "\n".join(lines) + "\n</resources>\n")
print("values-ru:", len(lines), "of", len(base_keys))
for f in glob.glob(os.path.join(RES, "values*/strings.xml")):
    t = open(f, encoding="utf-8").read()
    def rep(m):
        key, val = m.group(2), m.group(3)
        if key in ("AppName", "AppNameBeta"): val = LAUNCHER
        else: val = brand(val)
        return m.group(1) + val + m.group(4)
    n = STR.sub(rep, t)
    open(f, "w", encoding="utf-8").write(n)
    print(os.path.relpath(f, RES), "Telegram left:", len(re.findall("Telegram", n)))
