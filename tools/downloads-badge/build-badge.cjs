'use strict';

// Writes the README downloads badge as a self-contained SVG, styled like the
// shields.io badges next to it. Usage: node build-badge.cjs <count> <out.svg>
// Served from the `badges` branch so GitHub's image proxy never waits on shields.io.

const fs = require('fs');

const count = Number.parseInt(process.argv[2], 10);
const out = process.argv[3];
if (!Number.isFinite(count) || count < 0 || !out) {
  console.error('usage: node build-badge.cjs <count> <out.svg>');
  process.exit(1);
}

const value = String(count);
const labelWidth = 86;
const valueText = value.length * 70; // Verdana 11px digits, in tenths of a pixel
const valueWidth = Math.round(valueText / 10) + 10;
const width = labelWidth + valueWidth;
const valueX = labelWidth * 10 + Math.round((valueWidth * 10) / 2) - 10;

const logo = '<image x="5" y="3" width="14" height="14" href="data:image/svg+xml;base64,PHN2ZyBmaWxsPSIjZmZmZmZmIiByb2xlPSJpbWciIHZpZXdCb3g9IjAgMCAyNCAyNCIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj48dGl0bGU+R2l0SHViPC90aXRsZT48cGF0aCBkPSJNMTIgLjI5N2MtNi42MyAwLTEyIDUuMzczLTEyIDEyIDAgNS4zMDMgMy40MzggOS44IDguMjA1IDExLjM4NS42LjExMy44Mi0uMjU4LjgyLS41NzcgMC0uMjg1LS4wMS0xLjA0LS4wMTUtMi4wNC0zLjMzOC43MjQtNC4wNDItMS42MS00LjA0Mi0xLjYxQzQuNDIyIDE4LjA3IDMuNjMzIDE3LjcgMy42MzMgMTcuN2MtMS4wODctLjc0NC4wODQtLjcyOS4wODQtLjcyOSAxLjIwNS4wODQgMS44MzggMS4yMzYgMS44MzggMS4yMzYgMS4wNyAxLjgzNSAyLjgwOSAxLjMwNSAzLjQ5NS45OTguMTA4LS43NzYuNDE3LTEuMzA1Ljc2LTEuNjA1LTIuNjY1LS4zLTUuNDY2LTEuMzMyLTUuNDY2LTUuOTMgMC0xLjMxLjQ2NS0yLjM4IDEuMjM1LTMuMjItLjEzNS0uMzAzLS41NC0xLjUyMy4xMDUtMy4xNzYgMCAwIDEuMDA1LS4zMjIgMy4zIDEuMjMuOTYtLjI2NyAxLjk4LS4zOTkgMy0uNDA1IDEuMDIuMDA2IDIuMDQuMTM4IDMgLjQwNSAyLjI4LTEuNTUyIDMuMjg1LTEuMjMgMy4yODUtMS4yMy42NDUgMS42NTMuMjQgMi44NzMuMTIgMy4xNzYuNzY1Ljg0IDEuMjMgMS45MSAxLjIzIDMuMjIgMCA0LjYxLTIuODA1IDUuNjI1LTUuNDc1IDUuOTIuNDIuMzYuODEgMS4wOTYuODEgMi4yMiAwIDEuNjA2LS4wMTUgMi44OTYtLjAxNSAzLjI4NiAwIC4zMTUuMjEuNjkuODI1LjU3QzIwLjU2NSAyMi4wOTIgMjQgMTcuNTkyIDI0IDEyLjI5N2MwLTYuNjI3LTUuMzczLTEyLTEyLTEyIi8+PC9zdmc+"/>';

const text = (x, length, content) =>
  '<g transform="scale(.1)"><g aria-hidden="true" fill="#010101">' +
  `<text x="${x}" y="150" fill-opacity=".8" filter="url(#blur)" textLength="${length}">${content}</text>` +
  `<text x="${x}" y="150" fill-opacity=".3" textLength="${length}">${content}</text></g>` +
  `<text x="${x}" y="140" textLength="${length}">${content}</text></g>`;

const svg =
  `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="20" role="img" aria-label="downloads: ${value}">` +
  `<title>downloads: ${value}</title>` +
  '<filter id="blur"><feGaussianBlur stdDeviation="16"/></filter>' +
  '<linearGradient id="s" x2="0" y2="100%"><stop offset="0" stop-color="#bbb" stop-opacity=".1"/><stop offset="1" stop-opacity=".1"/></linearGradient>' +
  `<clipPath id="r"><rect width="${width}" height="20" rx="3"/></clipPath>` +
  `<g clip-path="url(#r)"><rect width="${labelWidth}" height="20" fill="#27303d"/>` +
  `<rect x="${labelWidth}" width="${valueWidth}" height="20" fill="#0d1117"/>` +
  `<rect width="${width}" height="20" fill="url(#s)"/></g>` +
  '<g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" text-rendering="geometricPrecision" font-size="110">' +
  logo +
  text(525, 590, 'downloads') +
  text(valueX, valueText, value) +
  '</g></svg>\n';

fs.writeFileSync(out, svg);
console.log(`downloads badge: ${value}`);
