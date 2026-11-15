/* Retra — non-blocking web font loading.
   A <link rel="stylesheet"> to Google Fonts in <head> is render-blocking: on a
   slow or captive network the first screen stays blank until it loads or fails.
   Inserting it from script makes it non-blocking, so the UI paints immediately
   with the system font and swaps to the web fonts when they arrive
   (display=swap). Inline onload tricks are not allowed by the page's CSP
   (script-src 'self'), hence this small file. */
(() => {
  'use strict';
  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = 'https://fonts.googleapis.com/css2?family=DM+Sans:wght@400;500;600;700;800'
    + '&family=Inter:wght@400;500;600;700;800'
    + '&family=Manrope:wght@400;500;600;700;800'
    + '&family=Poppins:wght@400;500;600;700;800'
    + '&family=Press+Start+2P&display=swap';
  document.head.appendChild(link);
})();
