import os
from PIL import Image

def rgb565(r, g, b):
    # Swap R and B to make it BGR565
    return ((b & 0xF8) << 8) | ((g & 0xFC) << 3) | (r >> 3)

in_dir = 'c:/Project/HOD Helmet/esp32-hud-firmware/icons'
out_c = 'c:/Project/HOD Helmet/esp32-hud-firmware/main/icons.c'
out_h = 'c:/Project/HOD Helmet/esp32-hud-firmware/main/icons.h'

files = [f for f in os.listdir(in_dir) if f.endswith('.png')]

with open(out_c, 'w', encoding='utf-8') as fc, open(out_h, 'w', encoding='utf-8') as fh:
    fh.write('#pragma once\n#include "lvgl.h"\n\n')
    fc.write('#include "icons.h"\n\n')
    
    for f in files:
        name = f.replace('.png', '')
        img = Image.open(os.path.join(in_dir, f)).convert('RGBA')
        width, height = img.width, img.height
        
        # blend with black background
        bg = Image.new('RGB', img.size, (0, 0, 0))
        bg.paste(img, mask=img.split()[3])
        
        data = []
        for y in range(height):
            for x in range(width):
                r, g, b = bg.getpixel((x, y))
                val = rgb565(r, g, b)
                # Swap the bytes for LV_COLOR_16_SWAP = 1
                data.append((val >> 8) & 0xFF)  # High byte first
                data.append(val & 0xFF)         # Low byte second
                
        fc.write(f'const uint8_t {name}_map[] = {{\n')
        for i in range(0, len(data), 16):
            chunk = data[i:i+16]
            fc.write('    ' + ', '.join([f'0x{b:02x}' for b in chunk]) + ',\n')
        fc.write('};\n\n')
        
        fc.write(f'const lv_img_dsc_t icon_{name} = {{\n')
        fc.write('    .header.always_zero = 0,\n')
        fc.write(f'    .header.w = {width},\n')
        fc.write(f'    .header.h = {height},\n')
        fc.write('    .data_size = ' + str(len(data)) + ',\n')
        fc.write('    .header.cf = LV_IMG_CF_TRUE_COLOR,\n')
        fc.write(f'    .data = {name}_map,\n')
        fc.write('};\n\n')
        
        fh.write(f'extern const lv_img_dsc_t icon_{name};\n')
