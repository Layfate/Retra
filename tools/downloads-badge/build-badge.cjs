'use strict';

// Writes the README downloads badge as a self-contained SVG, styled like the
// shields.io badges next to it. Usage: node build-badge.cjs <count> <out.svg>
// Committed to docs/badges/ on main so GitHub's image proxy never waits on shields.io.

const fs = require('fs');

const count = Number.parseInt(process.argv[2], 10);
const out = process.argv[3];
if (!Number.isFinite(count) || count < 0 || !out) {
  console.error('usage: node build-badge.cjs <count> <out.svg>');
  process.exit(1);
}

const value = String(count);
const height = 28;
const labelWidth = 119.25;
const valueText = value.length * 82.5; // Bold digits in the for-the-badge style, in tenths of a pixel
const valueWidth = valueText / 10 + 24;
const width = labelWidth + valueWidth;
const valueX = (labelWidth + valueWidth / 2) * 10;

const logo = '<image x="9" y="7" width="14" height="14" href="data:image/svg+xml;base64,PHN2ZyBmaWxsPSIjZmZmZmZmIiByb2xlPSJpbWciIHZpZXdCb3g9IjAgMCAyNCAyNCIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj48dGl0bGU+R2l0SHViPC90aXRsZT48cGF0aCBkPSJNMTIgLjI5N2MtNi42MyAwLTEyIDUuMzczLTEyIDEyIDAgNS4zMDMgMy40MzggOS44IDguMjA1IDExLjM4NS42LjExMy44Mi0uMjU4LjgyLS41NzcgMC0uMjg1LS4wMS0xLjA0LS4wMTUtMi4wNC0zLjMzOC43MjQtNC4wNDItMS42MS00LjA0Mi0xLjYxQzQuNDIyIDE4LjA3IDMuNjMzIDE3LjcgMy42MzMgMTcuN2MtMS4wODctLjc0NC4wODQtLjcyOS4wODQtLjcyOSAxLjIwNS4wODQgMS44MzggMS4yMzYgMS44MzggMS4yMzYgMS4wNyAxLjgzNSAyLjgwOSAxLjMwNSAzLjQ5NS45OTguMTA4LS43NzYuNDE3LTEuMzA1Ljc2LTEuNjA1LTIuNjY1LS4zLTUuNDY2LTEuMzMyLTUuNDY2LTUuOTMgMC0xLjMxLjQ2NS0yLjM4IDEuMjM1LTMuMjItLjEzNS0uMzAzLS41NC0xLjUyMy4xMDUtMy4xNzYgMCAwIDEuMDA1LS4zMjIgMy4zIDEuMjMuOTYtLjI2NyAxLjk4LS4zOTkgMy0uNDA1IDEuMDIuMDA2IDIuMDQuMTM4IDMgLjQwNSAyLjI4LTEuNTUyIDMuMjg1LTEuMjMgMy4yODUtMS4yMy42NDUgMS42NTMuMjQgMi44NzMuMTIgMy4xNzYuNzY1Ljg0IDEuMjMgMS45MSAxLjIzIDMuMjIgMCA0LjYxLTIuODA1IDUuNjI1LTUuNDc1IDUuOTIuNDIuMzYuODEgMS4wOTYuODEgMi4yMiAwIDEuNjA2LS4wMTUgMi44OTYtLjAxNSAzLjI4NiAwIC4zMTUuMjEuNjkuODI1LjU3QzIwLjU2NSAyMi4wOTIgMjQgMTcuNTkyIDI0IDEyLjI5N2MwLTYuNjI3LTUuMzczLTEyLTEyLTEyIi8+PC9zdmc+"/>';

const svg =
  `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" role="img" aria-label="downloads: ${value}">` +
  `<title>downloads: ${value}</title>` +
  `<g shape-rendering="crispEdges"><rect width="${labelWidth}" height="${height}" fill="#27303d"/>` +
  `<rect x="${labelWidth}" width="${valueWidth}" height="${height}" fill="#1c232e"/></g>` +
  '<g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" text-rendering="geometricPrecision" font-size="100">' +
  logo +
  '<text transform="scale(.1)" x="681.25" y="175" textLength="782.5">DOWNLOADS</text>' +
  `<text transform="scale(.1)" x="${valueX}" y="175" textLength="${valueText}" font-weight="bold">${value}</text>` +
  '</g></svg>\n';

fs.writeFileSync(out, svg);
console.log(`downloads badge: ${value}`);
