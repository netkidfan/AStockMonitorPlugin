#!/usr/bin/env python3
"""Generate a PNG plugin icon for Stock Monitor plugin."""

from PIL import Image, ImageDraw
import os

# Icon size
size = 128

# Colors
BG_COLOR = (26, 31, 54)  # Dark navy
GREEN = (74, 222, 128)    # Up candle
RED = (252, 96, 96)       # Down candle
MA_COLOR = (251, 191, 36) # MA line
GRID_COLOR = (42, 49, 88) # Grid lines

# Create image
img = Image.new('RGBA', (size, size), BG_COLOR)
draw = ImageDraw.Draw(img)

# Draw subtle grid
for y in [32, 64, 96]:
    draw.line([(20, y), (108, y)], fill=GRID_COLOR, width=1)

# Candlestick 1 (red, bearish)
draw.rectangle([26, 52, 38, 80], fill=RED)
draw.line([32, 44, 32, 52], fill=RED, width=2)
draw.line([32, 80, 32, 88], fill=RED, width=2)

# Candlestick 2 (green)
draw.rectangle([44, 38, 56, 74], fill=GREEN)
draw.line([50, 28, 50, 38], fill=GREEN, width=2)
draw.line([50, 74, 50, 82], fill=GREEN, width=2)

# Candlestick 3 (green)
draw.rectangle([62, 30, 74, 62], fill=GREEN)
draw.line([68, 22, 68, 30], fill=GREEN, width=2)
draw.line([68, 62, 68, 68], fill=GREEN, width=2)

# Candlestick 4 (green, highest)
draw.rectangle([80, 24, 92, 64], fill=GREEN)
draw.line([86, 16, 86, 24], fill=GREEN, width=2)
draw.line([86, 64, 86, 72], fill=GREEN, width=2)

# MA line
ma_points = [(32, 60), (50, 52), (68, 40), (86, 34)]
draw.line(ma_points, fill=MA_COLOR, width=3)

# MA dots
for x, y in ma_points:
    draw.ellipse([x-4, y-4, x+4, y+4], fill=MA_COLOR)

# Trend arrow
arrow_points = [(104, 86), (104, 100), (100, 92), (108, 92)]
draw.polygon(arrow_points, fill=GREEN)

# Add rounded corners (clip)
import math
mask = Image.new('L', (size, size), 0)
mask_draw = ImageDraw.Draw(mask)
mask_draw.rounded_rectangle([(0, 0), (size-1, size-1)], radius=24, fill=255)

# Apply corner mask
output = Image.new('RGBA', (size, size), BG_COLOR)
output.paste(img, (0, 0), mask)

# Save
output_path = r"C:\Users\91647\WorkBuddy\StockMonitorPlugin\src\main\resources\icons\plugin_icon.png"
output.save(output_path, 'PNG')
print(f"Saved: {output_path}")
