"""CI: перевіряє, що адреса блоку налаштувань у прошивці збігається з тією, куди пише додаток."""
import glob, os, re, struct, sys

APP = {"esp8266": 0x3FB000, "esp01": 0xFB000}   # ESP32-сімейство: 0x290000
board, build = sys.argv[1], sys.argv[2]

if board in APP:
    ld = {"esp8266": "eagle.flash.4m1m.ld", "esp01": "eagle.flash.1m64.ld"}[board]
    files = glob.glob(os.path.expanduser(f"~/.platformio/packages/framework-arduinoespressif8266/**/{ld}"), recursive=True)
    text = open(files[0]).read()
    got = int(re.search(r"_EEPROM_start\s*=\s*(0x[0-9a-fA-F]+)", text).group(1), 16) - 0x40200000
    want = APP[board]
else:
    data = open(os.path.join(build, "partitions.bin"), "rb").read()
    got = None
    for i in range(0, len(data), 32):
        magic, ptype, sub, off, size = struct.unpack_from("<HBBII", data, i)
        if magic != 0x50AA:
            break
        if ptype == 1 and sub == 0x82:   # data / spiffs
            got = off
    want = 0x290000

print(f"{board}: blob at 0x{got:X}, app writes 0x{want:X}")
sys.exit(0 if got == want else 1)
