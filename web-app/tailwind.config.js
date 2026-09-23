/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        trading: {
          bg: "#0B0E14",
          card: "#151922",
          border: "#232936",
          accent: "#2962FF",
          up: "#00C076",
          down: "#FF3B69",
          warning: "#F59E0B",
          muted: "#848E9C"
        }
      }
    },
  },
  plugins: [],
}
