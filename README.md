# Somewear Demo - Animated Temperature Grid

An animated gridview displaying 24-hour temperature forecast with smooth animations and real-time updates.

## Features

- **24-Hour Display**: Shows temperature for each hour of the day (12 AM - 11 PM)
- **Animated Grid**: Smooth fade-in animations when loading the page
- **Color-Coded Temperatures**: Visual temperature ranges with color coding:
  - Cold (< 10°C): Blue
  - Cool (10-18°C): Light Blue
  - Mild (18-24°C): Yellow/Gold
  - Warm (24-30°C): Orange
  - Hot (> 30°C): Red
- **Live Updates**: Temperatures update every 5 seconds with smooth transitions
- **Responsive Design**: Works on desktop, tablet, and mobile devices
- **Interactive**: Hover effects on each grid item

## How to Use

Simply open `index.html` in any modern web browser. No build process or dependencies required!

```bash
# Option 1: Open directly
open index.html

# Option 2: Use a simple HTTP server (recommended)
python -m http.server 8000
# Then visit http://localhost:8000
```

## Technical Details

- Pure HTML, CSS, and JavaScript (no frameworks required)
- CSS Grid layout for responsive design
- CSS animations and transitions for smooth visual effects
- Simulated temperature data with realistic daily variations
- Glassmorphism design aesthetic

## Temperature Simulation

The application simulates realistic temperature variations:
- Coldest temperatures around 4-6 AM
- Warmest temperatures around 2-4 PM
- Random variations to mimic real weather patterns