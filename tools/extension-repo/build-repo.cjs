// Builds the Retra extension repository (Retra extension API 1).
//
//   node tools/extension-repo/build-repo.cjs <https-base-url/> <output-dir>
//
// The default index lists one extension per website (Operation iDroid, PokéHarbor, Roms Games, ...), so
// users install only the sites they want. Each extension holds that site's GB/GBC/GBA sources.
// Optional --homebrew extensions:
//   * Retra Homebrew Sample  - the CC0 Retra Pad cartridge (samples/catalog-extension).
//   * Homebrew Hub           - every GB, GBC and GBA entry of the gbdev Homebrew Hub
//                              database (https://hh.gbdev.io), as searchable catalogs.
//
// Download policy: Homebrew Hub states that every entry has its own license and copyright
// holder. A game gets an authorized Download only when its entry declares a license that
// permits redistribution (see LICENSES below). The ROM is fetched from Homebrew Hub's own
// file host, pinned by size and SHA-256. Entries without such a license are listed
// catalog-only, with a link to the developer, and can be imported from a file the user
// obtains there. Hashes are cached in hashes.json so weekly rebuilds only fetch new files.
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {createHash} = require('node:crypto');
const sample = require('../../samples/catalog-extension/build-fixture.cjs');

const API = 'https://hh3.gbdev.io/api/search';
const FILES = 'https://hh3.gbdev.io/static';
const MAX_ROM = 128 * 1024 * 1024;
const PLATFORMS = ['GB', 'GBC', 'GBA'];
// Must match BrowseProtocol.LICENSE in BrowseModels.kt and allowedLicense in browse-ui.js.
const LICENSE = /^(CC0-1\.0|CC-BY(-NC)?(-SA|-ND)?(-[1-4]\.0)?|MIT|0BSD|ISC|BSD-[23]-Clause|Apache-2\.0|(L|A)?GPL-[23]\.0(-only|-or-later)?|MPL-2\.0|Zlib|Unlicense|WTFPL|FSFAP|public-domain|homebrew-freeware)$/;

/** Map one free-text license from the database to an SPDX-style id, or null when unknown. */
function normalizeLicensePart(value){
  const text = String(value || '').replace(/\(.*?\)/g, '').trim();
  if (!text) return null;
  const compact = text.replace(/\s+/g, '-').replace(/^GPLv?(\d)$/i, 'GPL-$1.0');
  const known = {mit:'MIT', zlib:'Zlib', unlicense:'Unlicense', '0bsd':'0BSD', isc:'ISC', fsfap:'FSFAP',
    'wtfpl':'WTFPL', 'wtfpl-2.0':'WTFPL', 'public-domain':'public-domain', 'cc0':'CC0-1.0', 'cc0-1.0':'CC0-1.0'};
  const candidate = known[compact.toLowerCase()] || compact.replace(/^cc-/i, 'CC-').replace(/-(by|nc|sa|nd)(?=-|$)/gi, part => part.toUpperCase());
  return LICENSE.test(candidate) ? candidate : null;
}
/** A combined license ("MIT / CC-BY-4.0 (Assets)", game + assets fields) is allowed only if every part is. */
function normalizeLicense(entry){
  const fields = [entry.license || entry.gameLicense, entry.assetsLicense].filter(value => value !== undefined && value !== '');
  if (!fields.length) return null;
  const parts = fields.flatMap(value => String(value).split(/\s*(?:\/|\bAND\b|,|\+)\s*/i)).filter(Boolean);
  const normalized = parts.map(normalizeLicensePart);
  return normalized.every(Boolean) ? [...new Set(normalized)].join(' AND ') : null;
}

const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function fetchRetry(url, as = 'json'){
  for (let attempt = 0; ; attempt++) {
    try {
      const response = await fetch(url, {headers:{'User-Agent':'Retra-Catalog-Builder/1'}, signal:AbortSignal.timeout(30000)});
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      return as === 'json' ? await response.json() : Buffer.from(await response.arrayBuffer());
    } catch (error) {
      if (attempt >= 3) throw new Error(`${url}: ${error.message}`);
      await sleep(1500 * (attempt + 1));
    }
  }
}
async function allEntries(){
  // Sort by slug (unique) so paging is stable; the default date sort has ties and skips entries.
  const first = await fetchRetry(`${API}?page=1&sort=slug&order=asc`);
  const entries = [...first.entries];
  const pages = Array.from({length:first.page_total - 1}, (_, i) => i + 2);
  while (pages.length) {
    const batch = pages.splice(0, 4); // Stay polite: four requests at a time.
    for (const result of await Promise.all(batch.map(n => fetchRetry(`${API}?page=${n}&sort=slug&order=asc`)))) entries.push(...result.entries);
  }
  return [...new Map(entries.map(entry => [entry.slug, entry])).values()];
}

const asList = value => Array.isArray(value) ? value : value === undefined || value === null || value === '' ? [] : [value];
// Only URLs BrowseProtocol.https accepts: no fragment, credentials, odd port or characters java.net.URI rejects.
// One bad URL would make Retra reject the whole catalog, so anything else is dropped here.
function acceptedUrl(url){
  if (typeof url !== 'string' || /[\s"<>\\^`{|}#]/.test(url)) return false;
  try {
    const parsed = new URL(url);
    return parsed.protocol === 'https:' && !parsed.username && !parsed.password && (parsed.port === '' || parsed.port === '443') &&
      Boolean(parsed.hostname) && parsed.hostname !== 'localhost' && !parsed.hostname.endsWith('.local');
  } catch (_) { return false; }
}
const httpsUrl = value => asList(value).find(acceptedUrl) || '';
const fileUrl = (entry, file) => `${FILES}/${entry.basepath}/entries/${encodeURIComponent(entry.slug)}/${file.split('/').map(encodeURIComponent).join('/')}`;
function romFile(entry){
  const roms = asList(entry.files).filter(file => file && /\.(gba|gbc|gb)$/i.test(file.filename || '') && file.playable !== false);
  return roms.find(file => file.default) || roms[0] || null;
}
function catalogId(slug){
  const id = String(slug).replace(/[^A-Za-z0-9._-]/g, '-').replace(/^[^A-Za-z0-9]+/, '').slice(0, 128);
  return id || null;
}
function developerNames(value){
  return asList(value).map(dev => typeof dev === 'string' ? dev : dev?.name).filter(Boolean).join(', ');
}

/** Convert one database entry to a Retra catalog game (without the download hash). */
function toGame(entry){
  const file = romFile(entry);
  const id = catalogId(entry.slug);
  if (!file || !id) return null;
  const platform = path.extname(file.filename).slice(1).toUpperCase();
  if (!PLATFORMS.includes(platform)) return null;
  const images = asList(entry.screenshots).filter(name => typeof name === 'string' && /\.(png|jpe?g|gif|webp)$/i.test(name));
  const cover = images.find(name => /(^|\/)cover\.[a-z]+$/i.test(name)) || images[0];
  const website = httpsUrl(entry.gameWebsite) || httpsUrl(entry.website) || httpsUrl(entry.repository) || `https://hh.gbdev.io/game/${encodeURIComponent(entry.slug)}/`;
  const tags = asList(entry.tags).filter(tag => typeof tag === 'string').map(tag => tag.trim()).filter(Boolean);
  const game = {
    id, title:String(entry.title || entry.slug).trim().slice(0, 200), platform,
    developer:developerNames(entry.developer) || undefined,
    year:/^\d{4}/.test(entry.date || '') ? entry.date.slice(0, 4) : undefined,
    genre:tags.length ? [...new Set(tags)].join(', ') : undefined,
    gameType:entry.typetag || undefined,
    updatedAt:Date.parse(entry.firstadded_date || entry.date || '') || 0,
    description:entry.description ? String(entry.description).slice(0, 4000) : undefined,
    cover:cover ? fileUrl(entry, cover) : undefined,
    screenshots:images.filter(name => name !== cover).slice(0, 8).map(name => fileUrl(entry, name)),
    website
  };
  if (!game.screenshots.length) delete game.screenshots;
  return {game, file, license:normalizeLicense(entry)};
}

async function addDownloads(rows, cache){
  const pending = rows.filter(row => row.license);
  let fetched = 0;
  while (pending.length) {
    await Promise.all(pending.splice(0, 4).map(async row => {
      const url = fileUrl(row.entry, row.file.filename);
      let known = cache[url];
      if (!known) {
        try {
          const bytes = await fetchRetry(url, 'buffer');
          if (bytes.length < 1 || bytes.length > MAX_ROM) return;
          known = cache[url] = {sha256:digest(bytes), size:bytes.length};
          fetched++;
        } catch (error) { console.warn(`skip download ${row.game.id}: ${error.message}`); return; }
      }
      const fileName = path.basename(row.file.filename).replace(/[^A-Za-z0-9._ -]/g, '_').replace(/\.(gba|gbc|gb)$/i, ext => ext.toLowerCase());
      row.game.romSha256 = known.sha256;
      row.game.download = {url, fileName, sha256:known.sha256, size:known.size, authorized:true, license:row.license, rightsUrl:row.game.website};
    }));
  }
  return fetched;
}

function writeJson(file, value){
  const data = Buffer.from(JSON.stringify(value, null, 2) + '\n');
  fs.mkdirSync(path.dirname(file), {recursive:true});
  fs.writeFileSync(file, data);
  return digest(data);
}
function icon(){
  // Original artwork: a cartridge silhouette in the four-shade handheld palette.
  const shades = [[155,188,15],[139,172,15],[48,98,48],[15,56,15]];
  return sample.png(96, 96, (x, y) => {
    const body = x >= 22 && x < 74 && y >= 14 && y < 84 && !(x >= 62 && y < 26);
    const label = x >= 30 && x < 66 && y >= 30 && y < 58;
    const pins = y >= 78 && y < 84 && x >= 28 && x < 68 && x % 6 < 3;
    return pins ? shades[1] : label ? shades[0] : body ? shades[2] : shades[3];
  });
}

// ---------- Website catalogs ----------
const PLATFORM_NAME = {GB:'Game Boy', GBC:'Game Boy Color', GBA:'Game Boy Advance'};
const SITES = [
  {id:'pokeharbor', name:'PokéHarbor', website:'https://www.pokeharbor.com/',
    api:'https://www.pokeharbor.com/wp-json/wp/v2/', searchUrl:'https://www.pokeharbor.com/?s={query}',
    icon:'https://www.pokeharbor.com/wp-content/uploads/2021/04/cropped-pokeharbor-favicon-192x192.png',
    categories:{GBA:'gba', GBC:'gbc'}},
  {id:'pokemundo', name:'PokeMundo', website:'https://pokemundo.com/en/',
    api:'https://pokemundo.com/en/wp-json/wp/v2/', searchUrl:'https://pokemundo.com/en/?s={query}',
    icon:'https://pokemundo.com/wp-content/uploads/2020/08/cropped-pikachu_icon-icons.com_67535-192x192.png',
    categories:{GBA:'hack-roms-pokemon-gba', GBC:'gbc'}, perPage:20},
  {id:'pokecoders', name:'PokéCoders', website:'https://www.pokemoncoders.com/',
    api:'https://www.pokemoncoders.com/wp-json/wp/v2/', searchUrl:'https://www.pokemoncoders.com/?s={query}',
    icon:'https://static.pokemoncoders.com/wp-content/uploads/2018/05/cropped-icon-amp-google.png',
    categories:{GBA:'gba-rom-hacks', GBC:'gbc-rom-hacks'}},
  {id:'pokemerald', name:'PokéMerald', website:'https://pokemerald.com/',
    api:'https://pokemerald.com/wp-json/wp/v2/', searchUrl:'https://pokemerald.com/?s={query}',
    logo:'pokemerald.png', categories:{GBA:'gba', GBC:'gbc'}}
];
// Site logos kept in tools/extension-repo/icons/ (each site's own favicon or app icon, so a source looks
// like its website). Used where the builder cannot or should not fetch the icon on every weekly rebuild.
const LOGOS = path.join(__dirname, 'icons');
async function siteLogo(site){
  return site.logo ? fs.readFileSync(path.join(LOGOS, site.logo)) : fetchRetry(site.icon, 'buffer');
}
const ROMSGAMES = {name:'Roms Games', website:'https://www.romsgames.net/',
  searchUrl:'https://www.romsgames.net/search/?q={query}', icon:'https://www.romsgames.net/image/apple-touch-icon.png'};
// Operation iDroid (PokeHacks): a Next.js site without a public API. Hack pages are listed in its
// sitemap and describe themselves with schema.org VideoGame JSON-LD. NDS hacks are skipped.
const IDROID = {id:'operationidroid', name:'Operation iDroid', website:'https://operationidroid.com/',
  sitemap:'https://operationidroid.com/sitemap.xml', icon:'https://operationidroid.com/assets/img/pokehacks-icon.png'};
// Sites behind a bot challenge (Cloudflare / Vercel) cannot be cataloged by the builder and are not
// worked around. They are website sources: Retra opens them in its in-app browser, where the user
// passes the challenge, searches, and downloads ROM, ZIP or patch files into Library.
// RHDI renders its database over a websocket and PokéCommunity blocks automated requests, so they are
// website sources too.
const WEB_ONLY = [
  {id:'romhacking', name:'Romhacking.net', website:'https://www.romhacking.net/hacks/',
    searchUrl:'https://www.romhacking.net/?page=hacks&title={query}&hacksearch=Go', logo:'romhacking.png'},
  {id:'hackdex', name:'Hackdex', website:'https://www.hackdex.app/', logo:'hackdex.png'},
  {id:'rhdi', name:'RHDI', website:'https://romhack.ing/', logo:'rhdi.png'},
  {id:'pokecommunity', name:'PokéCommunity', website:'https://www.pokecommunity.com/forums/completed-rom-hacks-showcase.184/',
    logo:'pokecommunity.png'}
];
// GameBrew: a MediaWiki with a public API. GBA hack and translation pages carry an infobox (title,
// author, version, date, description) and a page image.
const GAMEBREW = {id:'gamebrew', name:'GameBrew', website:'https://www.gamebrew.org/wiki/List_of_all_GBA_Rom_Hacks',
  api:'https://www.gamebrew.org/api.php', searchUrl:'https://www.gamebrew.org/index.php?search={query}', logo:'gamebrew.png',
  categories:['GBA rom hacks', 'GBA game hacks', 'GBA game translations']};
// FE Universe: the Fire Emblem hacking community (Discourse, public JSON). Completed projects tagged with a
// GBA base game (FE6, FE7 or FE8) become the catalog.
const FEU = {id:'feuniverse', name:'FE Universe', website:'https://feuniverse.us/c/projects/17',
  list:'https://feuniverse.us/tags/c/projects/17/complete_project.json', searchUrl:'https://feuniverse.us/search?q={query}',
  logo:'feuniverse.png', bases:{fe6_base:'The Binding Blade', fe7_base:'The Blazing Blade', fe8_base:'The Sacred Stones'}};
// One extension per website. Bump together when the package format or a site's sources change.
const EXTENSION_VERSION = {versionCode:1, versionName:'1.0'};
const EXTENSION_PREFIX = 'org.retra.';
// The former all-in-one bundle. It is no longer published; Retra uninstalls it once the repository lists the
// per-site extensions, and its saved source keys ("org.retra.romhackplaza/<source>") resolve to the same
// source IDs, which the per-site extensions keep.
const LEGACY_BUNDLE = 'org.retra.romhackplaza';
const GAME_PLATFORM = {'game boy advance':'GBA', 'game boy color':'GBC', 'game boy':'GB'};
function plainText(value){
  return String(value || '').replace(/<[^>]*>/g, ' ').replace(/&(#x[0-9a-f]+|#\d+|amp|quot|apos|lt|gt|nbsp|eacute|hellip);/gi, (all, entity) => {
    if (entity[0] === '#') {
      const code = entity[1].toLowerCase() === 'x' ? parseInt(entity.slice(2), 16) : Number(entity.slice(1));
      return code > 0 && code <= 0x10ffff && !(code >= 0xd800 && code <= 0xdfff) ? String.fromCodePoint(code) : '';
    }
    return {amp:'&', quot:'"', apos:"'", lt:'<', gt:'>', nbsp:' ', eacute:'é', hellip:'…'}[entity.toLowerCase()];
  }).replace(/\s+/g, ' ').trim();
}
function websiteGame(post, platform, site){
  if (!Number.isSafeInteger(post.id) || post.id <= 0 || !acceptedUrl(post.link) ||
      new URL(post.link).hostname !== new URL(site.website).hostname) return null;
  const title = plainText(post.title?.rendered).slice(0, 200);
  if (!title) return null;
  const media = post._embedded?.['wp:featuredmedia']?.[0];
  const cover = httpsUrl(media?.media_details?.sizes?.medium_large?.source_url) || httpsUrl(media?.source_url);
  return {id:String(post.id), title, platform, gameType:'romhack', genre:'Pokémon',
    updatedAt:Date.parse(post.modified_gmt ? post.modified_gmt + 'Z' : '') || 0,
    description:plainText(post.excerpt?.rendered).slice(0, 1000),
    cover:cover || undefined, website:post.link};
}
async function websiteCatalogs(site){
  const categories = await fetchRetry(site.api + 'categories?per_page=100&slug=' + Object.values(site.categories).join(','));
  const catalogs = [];
  for (const [platform, slug] of Object.entries(site.categories)) {
    const category = categories.find(row => row.slug === slug);
    assert(category && category.count > 0, site.name + ' category missing: ' + slug);
    const posts = [];
    // Category counts bound pagination; fail a rebuild if the API stops returning its games.
    const perPage = site.perPage || 100; // Slow servers time out on 100 posts with embedded artwork.
    for (let page = 1; page <= Math.ceil(category.count / perPage); page++) {
      const query = new URLSearchParams({categories:category.id, per_page:perPage, page, orderby:'id', order:'asc',
        _embed:'wp:featuredmedia', _fields:'id,link,title,excerpt,modified_gmt,_links,_embedded'});
      const batch = await fetchRetry(site.api + 'posts?' + query);
      assert(Array.isArray(batch) && batch.length, site.name + ' returned an empty page');
      posts.push(...batch);
    }
    const games = [...new Map(posts.map(post => websiteGame(post, platform, site)).filter(Boolean).map(game => [game.id, game])).values()];
    assert(games.length > 0 && games.length <= 10000, site.name + ' catalog size invalid');
    games.sort((a,b) => a.title.localeCompare(b.title, 'en', {sensitivity:'base'}));
    catalogs.push({platform, games});
  }
  return catalogs;
}
async function fetchText(url){
  for (let attempt = 0; ; attempt++) {
    try {
      const response = await fetch(url, {headers:{'User-Agent':'Mozilla/5.0 (compatible; Retra-Catalog-Builder/1)', Accept:'text/html,application/xml'},
        signal:AbortSignal.timeout(30000)});
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      return await response.text();
    } catch (error) {
      if (attempt >= 3) throw new Error(`${url}: ${error.message}`);
      await sleep(1500 * (attempt + 1));
    }
  }
}
/** "06/12/26" (MM/DD/YY) or an ISO date to epoch milliseconds, else 0. */
function siteDate(value){
  const us = String(value || '').match(/^(\d{1,2})\/(\d{1,2})\/(\d{2}|\d{4})$/);
  if (us) { const year = Number(us[3]) < 100 ? 2000 + Number(us[3]) : Number(us[3]); return Date.UTC(year, Number(us[1]) - 1, Number(us[2])) || 0; }
  return Date.parse(value || '') || 0;
}
/** Convert one Operation iDroid hack page to a catalog game, or null (NDS, malformed or off-site). */
function idroidGame(html, url){
  if (!acceptedUrl(url) || new URL(url).hostname !== new URL(IDROID.website).hostname) return null;
  const slug = new URL(url).pathname.match(/^\/hacks\/([^/]+)\/?$/)?.[1];
  const id = slug && catalogId(slug);
  if (!id) return null;
  let game;
  for (const [, json] of html.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g)) {
    try {
      const data = JSON.parse(json);
      game = (data['@graph'] || [data]).find(node => node && node['@type'] === 'VideoGame') || game;
    } catch (_) { /* Ignore malformed blocks. */ }
  }
  if (!game) return null;
  const platform = GAME_PLATFORM[String(game.gamePlatform || '').trim().toLowerCase()];
  const title = plainText(game.name).slice(0, 200);
  if (!platform || !title) return null;
  let cover = '';
  try { cover = new URL(String(game.image || ''), IDROID.website).href; } catch (_) { /* No artwork. */ }
  // og:title reads "Pokémon Unbound Download 2026 — Pokemon Fire Red ROM Hack".
  const base = plainText(html.match(/<meta property="og:title" content="([^"]*)"/)?.[1]).match(/— Pokemon (.+?) ROM Hack/i)?.[1];
  const version = plainText(game.version);
  return {id, title, platform, gameType:'romhack', genre:'Pokémon',
    developer:plainText(game.author?.name).slice(0, 200) || undefined,
    updatedAt:siteDate(game.dateModified),
    description:[base ? `Base game: Pokémon ${base}.` : '', version ? `Version ${version}.` : '',
      plainText(game.description).slice(0, 1000)].filter(Boolean).join(' ').slice(0, 1200) || undefined,
    cover:acceptedUrl(cover) ? cover : undefined, website:url};
}
async function idroidCatalogs(){
  const sitemap = await fetchText(IDROID.sitemap);
  const pages = [...new Set([...sitemap.matchAll(/<loc>(https:\/\/operationidroid\.com\/hacks\/[^<]+)<\/loc>/g)].map(match => match[1].trim()))];
  assert(pages.length > 0, IDROID.name + ' sitemap lists no hacks');
  const games = [];
  const queue = [...pages];
  while (queue.length) {
    // Two pages at a time with a pause between batches: a weekly rebuild stays light on the site.
    for (const result of await Promise.all(queue.splice(0, 2).map(async url => {
      try { return idroidGame(await fetchText(url), url); } catch (error) { console.warn(`skip ${url}: ${error.message}`); return null; }
    }))) if (result) games.push(result);
    await sleep(400);
  }
  const catalogs = [];
  for (const platform of ['GBA', 'GBC', 'GB']) {
    const rows = [...new Map(games.filter(game => game.platform === platform).map(game => [game.id, game])).values()]
      .sort((a, b) => a.title.localeCompare(b.title, 'en', {sensitivity:'base'}));
    if (rows.length) catalogs.push({platform, games:rows});
  }
  assert(catalogs.length, IDROID.name + ' returned no GB, GBC or GBA hacks');
  return catalogs;
}
/** Wiki markup to plain text: links keep their label; templates, bold/italic quotes and tags are dropped. */
function wikiText(value){
  return plainText(String(value || '').replace(/\{\{[^{}]*\}\}/g, ' ').replace(/\[\[(?:[^\]|]*\|)?([^\]]*)\]\]/g, '$1')
    .replace(/\[https?:\/\/[^\s\]]+\s*([^\]]*)\]/g, '$1').replace(/'{2,}/g, ''));
}
/** The fields of a page's GameBrew infobox ({{Infobox GBA Homebrews |key=value ...}}), or null. */
function infobox(text){
  const source = String(text || '');
  const start = source.search(/\{\{\s*Infobox\b/i);
  if (start < 0) return null;
  const fields = {};
  for (const line of source.slice(start).split('\n').slice(1)) {
    if (/^\s*\}\}/.test(line)) break;
    const match = line.match(/^\s*\|\s*([a-z_]+)\s*=(.*)$/i);
    if (match) fields[match[1].toLowerCase()] = match[2].trim();
  }
  return fields;
}
/** Convert one GameBrew page (revisions, pageimages and info props) to a GBA catalog game, or null. */
function gamebrewGame(page){
  const content = page?.revisions?.[0]?.slots?.main?.content ?? page?.revisions?.[0]?.['*'];
  const box = infobox(content);
  const url = page?.fullurl || page?.canonicalurl;
  if (!box || !Number.isSafeInteger(page.pageid) || page.pageid <= 0 || !acceptedUrl(url) || new URL(url).hostname !== 'www.gamebrew.org') return null;
  const title = (wikiText(box.title) || plainText(page.title).replace(/\s+GBA$/, '')).slice(0, 200);
  if (!title) return null;
  const date = String(box.lastupdated || '').match(/^(\d{4})[/-](\d{1,2})[/-](\d{1,2})/);
  const version = wikiText(box.version);
  const cover = httpsUrl(page.thumbnail?.source);
  return {id:String(page.pageid), title, platform:'GBA', gameType:'romhack', genre:wikiText(box.type).slice(0, 100) || 'Game Hacks',
    developer:wikiText(box.author).slice(0, 200) || undefined,
    year:date ? date[1] : undefined,
    updatedAt:date ? Date.UTC(Number(date[1]), Number(date[2]) - 1, Number(date[3])) || 0 : 0,
    description:[wikiText(box.description), version && !/^n\/?a$/i.test(version) ? `Version ${version}.` : ''].filter(Boolean).join(' ').slice(0, 1200) || undefined,
    cover:cover || undefined, website:url};
}
async function gamebrewCatalogs(){
  const ids = new Set();
  for (const category of GAMEBREW.categories) {
    let next = {};
    for (let page = 0; next && page < 50; page++) {
      const query = new URLSearchParams({action:'query', list:'categorymembers', cmtitle:'Category:' + category, cmnamespace:'0',
        cmlimit:'500', format:'json', formatversion:'2', ...next});
      const result = await fetchRetry(GAMEBREW.api + '?' + query);
      for (const member of result.query?.categorymembers || []) if (Number.isSafeInteger(member.pageid)) ids.add(member.pageid);
      next = result.continue || null;
    }
  }
  assert(ids.size > 0, GAMEBREW.name + ' lists no GBA hacks');
  const games = [];
  const queue = [...ids].sort((a, b) => a - b);
  while (queue.length) {
    // 50 pages per request (the API limit for page content); a pause keeps the weekly rebuild light.
    const query = new URLSearchParams({action:'query', prop:'revisions|pageimages|info', rvprop:'content', rvslots:'main',
      pithumbsize:'400', inprop:'url', format:'json', formatversion:'2', pageids:queue.splice(0, 50).join('|')});
    const result = await fetchRetry(GAMEBREW.api + '?' + query);
    for (const page of result.query?.pages || []) { const game = gamebrewGame(page); if (game) games.push(game); }
    await sleep(300);
  }
  const rows = [...new Map(games.map(game => [game.id, game])).values()].sort((a, b) => a.title.localeCompare(b.title, 'en', {sensitivity:'base'}));
  assert(rows.length > 0 && rows.length <= 10000, GAMEBREW.name + ' catalog size invalid');
  return [{platform:'GBA', games:rows}];
}
const tagName = tag => typeof tag === 'string' ? tag : tag?.name || '';
/** Convert one FE Universe topic to a GBA catalog game, or null when it is not a completed GBA-based hack. */
function feuGame(topic){
  if (!topic || topic.pinned || !Number.isSafeInteger(topic.id) || topic.id <= 0 || !/^[a-z0-9-]+$/.test(topic.slug || '')) return null;
  const base = (topic.tags || []).map(tagName).map(tag => FEU.bases[tag]).find(Boolean);
  const title = plainText(topic.title).slice(0, 200);
  if (!base || !title) return null;
  const cover = httpsUrl(topic.image_url);
  const created = Date.parse(topic.created_at || '') || 0;
  return {id:String(topic.id), title, platform:'GBA', gameType:'romhack', genre:'Fire Emblem',
    year:created ? String(new Date(created).getUTCFullYear()) : undefined,
    updatedAt:Date.parse(topic.last_posted_at || topic.bumped_at || '') || created,
    description:[`Base game: Fire Emblem: ${base}.`, plainText(topic.excerpt)].filter(Boolean).join(' ').slice(0, 1200),
    cover:cover && new URL(cover).hostname === 'feuniverse.us' ? cover : undefined,
    website:`https://feuniverse.us/t/${topic.slug}/${topic.id}`};
}
async function feuCatalogs(){
  const games = [];
  for (let page = 0; page < 100; page++) {
    const result = await fetchRetry(FEU.list + '?page=' + page);
    const topics = result.topic_list?.topics || [];
    for (const topic of topics) { const game = feuGame(topic); if (game) games.push(game); }
    if (!topics.length || !result.topic_list?.more_topics_url) break;
    await sleep(400);
  }
  const rows = [...new Map(games.map(game => [game.id, game])).values()].sort((a, b) => a.title.localeCompare(b.title, 'en', {sensitivity:'base'}));
  assert(rows.length > 0 && rows.length <= 10000, FEU.name + ' catalog size invalid');
  return [{platform:'GBA', games:rows}];
}
// ---------- Box art (libretro-thumbnails, also used by ArtworkRepository) ----------
const BOXART_REPOS = {GB:'Nintendo_-_Game_Boy', GBC:'Nintendo_-_Game_Boy_Color', GBA:'Nintendo_-_Game_Boy_Advance'};
const REGION_RANK = ['USA', 'World', 'Europe', 'Japan'];
async function boxartIndex(){
  const index = {};
  const headers = {'User-Agent':'Retra-Catalog-Builder/1', Accept:'application/vnd.github+json'};
  if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  for (const [platform, repo] of Object.entries(BOXART_REPOS)) {
    try {
      let tree;
      for (let attempt = 0; !tree; attempt++) {
        try {
          const response = await fetch(`https://api.github.com/repos/libretro-thumbnails/${repo}/git/trees/master?recursive=1`, {headers, signal:AbortSignal.timeout(60000)});
          if (!response.ok) throw new Error(`HTTP ${response.status}`);
          tree = await response.json();
        } catch (error) {
          // A missing index drops every cover of that platform, so retry before giving up.
          if (attempt >= 3) throw error;
          await sleep(2000 * (attempt + 1));
        }
      }
      // Regular files only: symlinked names would serve link text instead of an image.
      index[platform] = tree.tree.filter(item => item.type === 'blob' && item.mode === '100644' && /^Named_Boxarts\/[^/]+\.png$/.test(item.path))
        .map(item => {
          const name = item.path.slice('Named_Boxarts/'.length, -4);
          const regions = (name.match(/\(([^)]*)\)/) || [])[1] || '';
          const rank = REGION_RANK.findIndex(region => regions.split(/,\s*/).includes(region));
          return {name, rank:rank < 0 ? REGION_RANK.length : rank,
            clean:!/\((Beta|Proto|Demo|Sample|Hack|Unl|Pirate|Kiosk|Aftermarket|Homebrew|Program|Test|Competition)/i.test(name)};
        }).filter(entry => entry.clean);
      console.log(`box art index ${platform}: ${index[platform].length}`);
    } catch (error) {
      console.warn(`box art index ${platform} unavailable: ${error.message}`);
      index[platform] = [];
    }
  }
  return index;
}
function boxartUrl(platform, name){
  // Same file-name rules as ArtworkRepository.boxartUrl.
  return `https://raw.githubusercontent.com/libretro-thumbnails/${BOXART_REPOS[platform]}/master/Named_Boxarts/${encodeURIComponent(name.replace(/[&*/:"<>?\\|]/g, '_'))}.png`;
}
// ---------- Official games (catalog only) ----------
// One entry per licensed GB/GBC/GBA release from No-Intro, with available libretro-thumbnails art,
// with developer, publisher, release year and genre from libretro-database. These are catalog entries
// only: the website download flow is separate from verified distributable catalog files.
const METADAT = 'https://raw.githubusercontent.com/libretro/libretro-database/master/metadat';
const DAT_NAMES = {GB:'Nintendo - Game Boy', GBC:'Nintendo - Game Boy Color', GBA:'Nintendo - Game Boy Advance'};
const artKey = name => name.replace(/[&*/:"<>?\\|]/g, '_').toLowerCase();
async function metadata(platform){
  const fields = {developer:'developer', publisher:'publisher', releaseyear:'releaseyear', genre:'genre'};
  const result = {names:new Map(), official:new Set(), releases:[]};
  // No-Intro: the list of real cartridge releases. Box art for hacks or fan works is not in it.
  try {
    const text = (await fetchRetry(`${METADAT}/no-intro/${encodeURIComponent(DAT_NAMES[platform])}.dat`, 'buffer')).toString('utf8');
    for (const [, block] of text.matchAll(/game \(([\s\S]*?)\n\)/g)) {
      const name = block.match(/\bname "([^"]*)"/)?.[1];
      if (!name || /\((Beta|Proto|Demo|Sample|Hack|Unl|Pirate|Kiosk|Aftermarket|Homebrew|Program|Test|Competition)\b/i.test(block)) continue;
      result.official.add(artKey(name));
      result.releases.push(name);
    }
    assert(result.official.size, 'Empty No-Intro release list');
  } catch (error) { throw new Error(`No-Intro list ${platform} unavailable: ${error.message}`); }
  for (const [file, field] of Object.entries(fields)) {
    result[field] = new Map();
    try {
      const text = (await fetchRetry(`${METADAT}/${file}/${encodeURIComponent(DAT_NAMES[platform])}.dat`, 'buffer')).toString('utf8');
      for (const [, block] of text.matchAll(/game \(([\s\S]*?)\n\)/g)) {
        const name = (block.match(/comment "([^"]*)"/) || [])[1];
        const value = (block.match(new RegExp(`\\b${field} "([^"]*)"`)) || [])[1];
        if (!name || !value) continue;
        result[field].set(artKey(name), value);
        result.names.set(artKey(name), name); // Original spelling (box art file names replace & : / etc.).
      }
    } catch (error) { console.warn(`metadata ${platform} ${file} unavailable: ${error.message}`); }
  }
  return result;
}
/** "Legend of Zelda, The - Link's Awakening DX" -> "The Legend of Zelda - Link's Awakening DX". */
function displayTitle(base){
  return base.replace(/^(.*?), (The|A|An)( - .*)?$/, (_, title, article, rest = '') => `${article} ${title}${rest}`);
}
function officialGames(platform, entries, meta){
  const covers = new Map(entries.map(entry => [artKey(entry.name), entry.name]));
  // Include cartridge releases without a thumbnail, rather than silently dropping those games.
  const releases = (meta.releases || []).map(name => {
    const regions = ((name.match(/\(([^)]*)\)/) || [])[1] || '').split(/,\s*/);
    const rank = REGION_RANK.findIndex(region => regions.includes(region));
    return {name, rank:rank < 0 ? REGION_RANK.length : rank};
  });
  const best = new Map();
  for (const entry of [...entries, ...releases]) {
    if (meta.official.size && !meta.official.has(artKey(entry.name))) continue;
    if (/\[/.test(entry.name)) continue; // [Hack by …], [T-En by …]
    const base = entry.name.replace(/\s*\(.*$/, '').trim();
    const key = base.toLowerCase();
    const current = best.get(key);
    if (!current || entry.rank < current.rank || (entry.rank === current.rank && entry.name.length < current.name.length)) best.set(key, entry);
  }
  const ids = new Set();
  return [...best.values()].map(entry => {
    const key = artKey(entry.name);
    const original = meta.names.get(key) || entry.name;
    const title = displayTitle(original.replace(/\s*\(.*$/, '').trim());
    const region = ((entry.name.match(/\(([^)]*)\)/) || [])[1] || '').split(/,\s*/).filter(region => /^[A-Z][a-z]+$|^USA$/.test(region)).join(', ');
    const year = (meta.releaseyear.get(key) || '').match(/^\d{4}$/)?.[0];
    let id = catalogId(`${title}`.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '-')) || 'game';
    for (let n = 2; ids.has(id); n++) id = catalogId(`${id.replace(/-\d+$/, '')}-${n}`);
    ids.add(id);
    const baseTitle = entry.name.replace(/\s*\(.*$/, '');
    const art = covers.get(key) || entries.find(row => row.name.replace(/\s*\(.*$/, '') === baseTitle)?.name;
    const cover = art ? boxartUrl(platform, art) : '';
    return {
      id, title, platform,
      developer:meta.developer.get(key), publisher:meta.publisher.get(key), year,
      genre:meta.genre.get(key), region:region || undefined, gameType:'official',
      updatedAt:year ? Date.UTC(Number(year), 0, 1) : 0,
      description:`Official ${PLATFORM_NAME[platform]} release${region ? ` (${region})` : ''}. ` +
        'Add it to your Library, then use Import ROM to link your own cartridge dump.',
      cover:acceptedUrl(cover) ? cover : undefined
    };
  }).sort((a, b) => a.title.localeCompare(b.title, 'en', {sensitivity:'base'}));
}

function siteSummary(kind, platforms, games){
  const list = platforms.join(', ');
  if (kind === 'website') return `${list}: website source, searched and downloaded in Retra's in-app browser`;
  if (kind === 'official') return `${list}: official releases (No-Intro) with box art, ${games.toLocaleString('en-US')} games; each opens a title search on the website`;
  return `${list}: ${games.toLocaleString('en-US')} ROM hacks with cover art, plus website search and downloads into Library`;
}

function readme(base, extensions){
  const url = `${base}index.json`;
  return `# Retra extension repository\n\nCatalog extensions for Retra (extension API 1). Add this URL in Retra under ` +
    `**Browse → Extensions → ⋮ → Extension repos → Add**:\n\n\`\`\`\n${url}\n\`\`\`\n\n## Extensions\n\n` +
    'Each website is its own extension. Install the ones you want under **Browse → Extensions**; their GB, GBC and GBA ' +
    'sources then appear in **Sources**, where you can open and search them.\n\n' +
    extensions.map(ext => `- **${ext.name}**: ${ext.summary}`).join('\n') +
    '\n\nThese extensions replace the former all-in-one Retra Sources extension. Retra removes Retra Sources after the repository refreshes; ' +
    'games you added to Library keep working, and pinned sources stay pinned once you install their website again.\n\n' +
    '## How the sources work\n\n' +
    '| Source | Platforms | In Retra |\n|---|---|---|\n' +
    '| Roms Games | GB, GBC, GBA | Official releases (No-Intro) with box art; each game opens a Roms Games title search |\n' +
    '| PokéHarbor | GBC, GBA | Hack catalog from its public WordPress API, with artwork |\n' +
    '| PokeMundo | GBC, GBA | Hack catalog from its public WordPress API, with artwork |\n' +
    '| PokéCoders | GBC, GBA | Hack catalog from its public WordPress API, with artwork |\n' +
    '| PokéMerald | GBC, GBA | Hack catalog from its public WordPress API, with artwork |\n' +
    '| Operation iDroid | GBC, GBA | Hack catalog from its sitemap and page data, with artwork; NDS hacks are skipped |\n' +
    '| GameBrew | GBA | GBA hacks and translations from its public wiki API, with author, version and artwork |\n' +
    '| FE Universe | GBA | Completed Fire Emblem hacks (FE6, FE7 and FE8 bases) from its public forum API, with artwork |\n' +
    '| Romhacking.net | GB, GBC, GBA | Website source: search and download in the in-app browser |\n' +
    '| Hackdex | GB, GBC, GBA | Website source: browse and download in the in-app browser |\n' +
    '| RHDI | GB, GBC, GBA | Website source: the Romhacking Database Initiative, browsed in the in-app browser |\n' +
    '| PokéCommunity | GB, GBC, GBA | Website source: its Completed ROM Hacks Showcase forum, browsed in the in-app browser |\n\n' +
    'Romhacking.net, Hackdex and PokéCommunity protect their pages with a bot check, and RHDI has no public listing, so the builder cannot list their games. Open them from the source page; ' +
    'Retra shows the site in its in-app browser, where you can pass the check, search and download.\n\n' +
    'Search the installed catalogs in Retra. Use Download on website on a game, or Search website in the source menu, to browse the original site inside Retra. ' +
    'Supported GB, GBC, GBA, ZIP, IPS, UPS and BPS downloads are imported into Library. Patches require your own compatible base ROM.\n\n' +
    'Official games come from the No-Intro release lists in libretro-database, with available box art from libretro-thumbnails. ' +
    'Roms Games entries open a title search, so availability depends on the website. ' +
    'Catalogs refresh weekly; the in-app website search can find newer entries. Files are not re-hosted, and commercial downloads are not labeled as openly licensed.\n\n' +
    `These files are generated by \`tools/extension-repo/build-repo.cjs\` in \`extensions/\` on \`main\` and refreshed weekly. Do not edit them by hand.\n`;
}

async function build(base, out, {homebrew = false} = {}){
  assert(new URL(base).protocol === 'https:' && base.endsWith('/'), 'Base must be an https URL ending in /');
  fs.mkdirSync(out, {recursive:true});
  const cacheFile = path.join(out, 'hashes.json');
  const cache = fs.existsSync(cacheFile) ? JSON.parse(fs.readFileSync(cacheFile, 'utf8')) : {};
  // Remember the last published website catalogs and logos: if one site is down during a rebuild,
  // its previous catalog is republished instead of failing every other source.
  const previous = new Map();
  const previousDir = path.join(out, 'sources');
  if (fs.existsSync(previousDir)) {
    for (const name of fs.readdirSync(previousDir)) if (/\.(json|png)$/.test(name)) previous.set(name, fs.readFileSync(path.join(previousDir, name)));
  }
  /** A site's fresh catalogs, or its last published ones (per platform) when the site cannot be reached. */
  async function catalogsOrPrevious(id, name, load){
    try { return await load(); } catch (error) {
      const kept = ['gba', 'gbc', 'gb'].map(platform => {
        const bytes = previous.get(`${id}-${platform}.json`);
        if (!bytes) return null;
        const games = JSON.parse(bytes.toString('utf8')).games || [];
        return games.length ? {platform:platform.toUpperCase(), games} : null;
      }).filter(Boolean);
      if (!kept.length) throw error;
      console.warn(`${name} unavailable (${error.message}); republishing its previous catalog`);
      return kept;
    }
  }
  async function logoOrPrevious(site, file){
    try { return await siteLogo(site); } catch (error) {
      if (previous.has(file)) { console.warn(`${file}: ${error.message}; keeping the previous logo`); return previous.get(file); }
      throw error;
    }
  }
  // Start from an empty tree (keeping git metadata and the ROM hash cache) so removed extensions disappear.
  for (const name of fs.readdirSync(out)) if (!['.git', 'hashes.json'].includes(name)) fs.rmSync(path.join(out, name), {recursive:true, force:true});
  const extensions = [];
  const summaries = [];
  const boxarts = await boxartIndex();

  // Catalog and logo files stay in sources/ (stable URLs); each website's extension package is
  // packages/<site>.json and lists only that site's sources.
  const folder = 'sources';
  const dir = path.join(out, folder);
  fs.mkdirSync(dir, {recursive:true});
  const sites = [];
  const sourceRow = (id, name, platforms, latest, icon, website, searchUrl) => ({id, name, platforms, latest,
    catalogUrl:base + folder + '/' + id + '.json', icon:base + folder + '/' + icon, website, ...(searchUrl ? {searchUrl} : {})});
  const siteEntry = (site, kind) => { const entry = {site, kind, sources:[], games:0}; sites.push(entry); return entry; };

  for (const site of SITES) {
    const entry = siteEntry(site, 'catalog');
    fs.writeFileSync(path.join(dir, site.id + '.png'), await logoOrPrevious(site, site.id + '.png'));
    for (const {platform, games} of await catalogsOrPrevious(site.id, site.name, () => websiteCatalogs(site))) {
      const id = site.id + '-' + platform.toLowerCase();
      writeJson(path.join(dir, id + '.json'), {apiVersion:1, games});
      entry.sources.push(sourceRow(id, site.name + ' ' + platform, [platform], true, site.id + '.png', site.website, site.searchUrl));
      entry.games += games.length;
      console.log(site.name + ' ' + platform + ': ' + games.length + ' games, ' + games.filter(game => game.cover).length + ' covers');
    }
  }
  {
    const entry = siteEntry(IDROID, 'catalog');
    fs.writeFileSync(path.join(dir, IDROID.id + '.png'), await logoOrPrevious(IDROID, IDROID.id + '.png'));
    for (const {platform, games} of await catalogsOrPrevious(IDROID.id, IDROID.name, idroidCatalogs)) {
      const id = IDROID.id + '-' + platform.toLowerCase();
      writeJson(path.join(dir, id + '.json'), {apiVersion:1, games});
      entry.sources.push(sourceRow(id, IDROID.name + ' ' + platform, [platform], true, IDROID.id + '.png', IDROID.website + '?platform=' + platform));
      entry.games += games.length;
      console.log(IDROID.name + ' ' + platform + ': ' + games.length + ' games, ' + games.filter(game => game.cover).length + ' covers');
    }
  }
  for (const site of [GAMEBREW, FEU]) {
    const entry = siteEntry(site, 'catalog');
    fs.writeFileSync(path.join(dir, site.id + '.png'), await siteLogo(site));
    for (const {platform, games} of await catalogsOrPrevious(site.id, site.name, site === GAMEBREW ? gamebrewCatalogs : feuCatalogs)) {
      const id = site.id + '-' + platform.toLowerCase();
      writeJson(path.join(dir, id + '.json'), {apiVersion:1, games});
      entry.sources.push(sourceRow(id, site.name + ' ' + platform, [platform], true, site.id + '.png', site.website, site.searchUrl));
      entry.games += games.length;
      console.log(site.name + ' ' + platform + ': ' + games.length + ' games, ' + games.filter(game => game.cover).length + ' covers');
    }
  }
  for (const site of WEB_ONLY) {
    const entry = siteEntry(site, 'website');
    fs.writeFileSync(path.join(dir, site.id + '.png'), await siteLogo(site));
    writeJson(path.join(dir, site.id + '.json'), {apiVersion:1, games:[]});
    entry.sources.push(sourceRow(site.id, site.name, PLATFORMS, false, site.id + '.png', site.website, site.searchUrl));
    console.log(site.name + ': website source');
  }
  {
    const entry = siteEntry({id:'romsgames', ...ROMSGAMES}, 'official');
    fs.writeFileSync(path.join(dir, 'romsgames.png'), await logoOrPrevious(ROMSGAMES, 'romsgames.png'));
    for (const platform of PLATFORMS) {
      const games = officialGames(platform, boxarts[platform] || [], await metadata(platform));
      assert(games.length > 0 && games.length <= 10000, 'Official catalog size invalid');
      const file = 'official-' + platform.toLowerCase();
      for (const game of games) game.website = ROMSGAMES.searchUrl.replace('{query}', encodeURIComponent(game.title));
      writeJson(path.join(dir, file + '.json'), {apiVersion:1, games});
      entry.sources.push(sourceRow(file, ROMSGAMES.name + ' ' + platform, [platform], true, 'romsgames.png',
        ROMSGAMES.website + 'roms/' + ({GB:'gameboy', GBC:'gameboy-color', GBA:'gameboy-advance'})[platform] + '/', ROMSGAMES.searchUrl));
      entry.games += games.length;
      console.log('Official ' + platform + ': ' + games.length + ' games, ' + games.filter(game => game.cover).length + ' covers');
    }
  }

  for (const entry of sites.sort((a, b) => a.site.name.localeCompare(b.site.name, 'en', {sensitivity:'base'}))) {
    const {site, kind, sources} = entry;
    if (!sources.length) continue;
    const id = EXTENSION_PREFIX + site.id;
    const platforms = PLATFORMS.filter(platform => sources.some(source => source.platforms.includes(platform)));
    const sha256 = writeJson(path.join(out, 'packages', site.id + '.json'),
      {apiVersion:1, id, versionCode:EXTENSION_VERSION.versionCode, sources});
    const host = new URL(site.website).host.replace(/^www\./, '');
    extensions.push({id, name:site.name, publisher:`Retra contributors (catalog of ${host})`,
      versionCode:EXTENSION_VERSION.versionCode, versionName:EXTENSION_VERSION.versionName,
      format:'retra.catalog', apiMin:1, apiMax:1, platforms,
      packageUrl:base + 'packages/' + site.id + '.json', sha256, icon:base + folder + '/' + site.id + '.png'});
    summaries.push({name:site.name, summary:siteSummary(kind, platforms, entry.games)});
  }

  if (homebrew) await buildHomebrew(base, out, cache, extensions, summaries);
  if (Object.keys(cache).length) fs.writeFileSync(cacheFile, JSON.stringify(Object.fromEntries(Object.entries(cache).sort()), null, 1) + '\n');
  writeJson(path.join(out, 'index.json'), {schemaVersion:1, name:'Retra extensions', extensions});
  fs.writeFileSync(path.join(out, 'README.md'), readme(base, summaries));
}

/** Optional (--homebrew): the CC0 Retra Pad sample and the Homebrew Hub catalogs with authorized downloads. */
async function buildHomebrew(base, out, cache, extensions, summaries){
  // Retra Homebrew Sample (writes its own files and a one-entry index, replaced by the combined index later).
  sample.build(base, out);
  const sampleEntry = JSON.parse(fs.readFileSync(path.join(out, 'index.json'), 'utf8')).extensions[0];

  // 2. Homebrew Hub catalogs, one source per platform.
  const entries = await allEntries();
  const rows = entries.map(entry => { const row = toGame(entry); return row && {...row, entry}; }).filter(Boolean);
  const fetched = await addDownloads(rows, cache);
  const dir = path.join(out, 'homebrewhub');
  fs.mkdirSync(dir, {recursive:true});
  fs.writeFileSync(path.join(dir, 'icon.png'), icon());
  const sources = [];
  for (const platform of PLATFORMS) {
    const ids = new Set();
    const games = rows.filter(row => row.game.platform === platform && !ids.has(row.game.id) && ids.add(row.game.id))
      .map(row => row.game).sort((a, b) => a.title.localeCompare(b.title, 'en', {sensitivity:'base'}));
    assert(games.length <= 10000, 'API 1 catalogs hold at most 10,000 games');
    writeJson(path.join(dir, `${platform.toLowerCase()}.json`), {apiVersion:1, games});
    sources.push({id:platform.toLowerCase(), name:`Homebrew Hub ${platform}`, platforms:[platform], latest:true,
      catalogUrl:`${base}homebrewhub/${platform.toLowerCase()}.json`, icon:`${base}homebrewhub/icon.png`});
    console.log(`${platform}: ${games.length} games, ${games.filter(game => game.download).length} with authorized downloads`);
  }
  const packageSha = writeJson(path.join(dir, 'extension.json'), {apiVersion:1, id:'org.retra.homebrewhub', versionCode:1, sources});
  extensions.push(sampleEntry, {
    id:'org.retra.homebrewhub', name:'Homebrew Hub', publisher:'Retra contributors (catalog data from hh.gbdev.io)',
    versionCode:1, versionName:'1.0', format:'retra.catalog', apiMin:1, apiMax:1, platforms:PLATFORMS,
    packageUrl:`${base}homebrewhub/extension.json`, sha256:packageSha, icon:`${base}homebrewhub/icon.png`
  });
  summaries.push({name:'Retra Homebrew Sample', summary:'Retra Pad, a CC0 Game Boy homebrew cartridge.'},
    {name:'Homebrew Hub', summary:'GB, GBC and GBA homebrew from the gbdev Homebrew Hub, with authorized downloads for openly licensed games.'});
  console.log(`Homebrew Hub entries: ${entries.length}, catalog games: ${rows.length}, newly hashed ROMs: ${fetched}`);
}

if (require.main === module) {
  const args = process.argv.slice(2);
  const homebrew = args.includes('--homebrew');
  const [base, out] = args.filter(arg => !arg.startsWith('--'));
  if (!base || !out) { console.error('Usage: node tools/extension-repo/build-repo.cjs <https-base-url/> <output-dir> [--homebrew]'); process.exit(2); }
  build(base, path.resolve(out), {homebrew}).catch(error => { console.error(error); process.exit(1); });
}
module.exports = {build, normalizeLicense, toGame, acceptedUrl, SITES, ROMSGAMES, IDROID, GAMEBREW, FEU, WEB_ONLY, EXTENSION_VERSION, EXTENSION_PREFIX, LEGACY_BUNDLE, siteSummary, readme, plainText, websiteGame,
  websiteCatalogs, idroidGame, gamebrewGame, gamebrewCatalogs, feuGame, feuCatalogs, infobox, wikiText, siteDate, displayTitle, officialGames, LICENSE};
