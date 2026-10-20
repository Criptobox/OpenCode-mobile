#!/usr/bin/env python3
"""Descarga PRoot (build de Termux, arm64) y lo deja como librerías nativas de la app."""
import os, re, shutil, subprocess, tempfile, urllib.request

BASE = "https://packages.termux.dev/apt/termux-main/"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JNI = os.path.join(ROOT, "app/src/main/jniLibs/arm64-v8a")
ASSETS = os.path.join(ROOT, "app/src/main/assets")
PFX = "data/data/com.termux/files/usr"
SKIP = {"termux-exec", "termux-tools", "termux-core", "termux-keyring", "apt", "dpkg"}

def get(url):
    return urllib.request.urlopen(url, timeout=120).read()

def parse(text):
    pk = {}
    for block in text.split("\n\n"):
        d = {}
        for line in block.split("\n"):
            if ": " in line and not line.startswith(" "):
                k, v = line.split(": ", 1)
                d[k] = v
        if "Package" in d:
            pk[d["Package"]] = d
    return pk

def deps(d):
    return [p.strip().split(" ")[0] for p in d.get("Depends", "").split(",") if p.strip()]

def needed(path):
    out = subprocess.run(["readelf", "-d", path], capture_output=True, text=True).stdout
    return re.findall(r"\(NEEDED\).*\[(.+?)\]", out)

idx = parse(get(BASE + "dists/stable/main/binary-aarch64/Packages").decode())
order, stack = [], ["proot"]
while stack:
    n = stack.pop()
    if n in order or n in SKIP or n not in idx:
        continue
    order.append(n)
    stack.extend(deps(idx[n]))
print("Paquetes Termux:", order)

tmp = tempfile.mkdtemp()
fs = os.path.join(tmp, "fs")
os.makedirs(fs)
for n in order:
    deb = os.path.join(tmp, n + ".deb")
    with open(deb, "wb") as f:
        f.write(get(BASE + idx[n]["Filename"]))
    data = subprocess.run(["ar", "p", deb, "data.tar.xz"], capture_output=True, check=True).stdout
    subprocess.run(["tar", "xJ", "-C", fs], input=data, check=True)

usr = os.path.join(fs, PFX)
os.makedirs(JNI, exist_ok=True)
os.makedirs(ASSETS, exist_ok=True)
shutil.copy(os.path.join(usr, "bin/proot"), os.path.join(JNI, "libproot.so"))
shutil.copy(os.path.join(usr, "libexec/proot/loader"), os.path.join(JNI, "libproot-loader.so"))

libdir = os.path.join(usr, "lib")
lines, seen = [], set()
queue = needed(os.path.join(usr, "bin/proot"))
print("proot necesita:", queue)
while queue:
    so = queue.pop()
    if so in seen:
        continue
    seen.add(so)
    src = os.path.join(libdir, so)
    if not os.path.exists(src):
        continue  # librería del sistema (libc, libdl, ...)
    jni = so.split(".so")[0] + ".so"
    shutil.copy(src, os.path.join(JNI, jni))
    lines.append(f"{so} {jni}")
    queue.extend(needed(src))
with open(os.path.join(ASSETS, "proot_libs.txt"), "w") as f:
    f.write("\n".join(lines) + "\n")
print("Listo:", os.listdir(JNI), lines)
