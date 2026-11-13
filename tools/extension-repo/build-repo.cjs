// Builds the Retra extension repository (Retra extension API 1).
//
//   node tools/extension-repo/build-repo.cjs <https-base-url/> <output-dir>
//
// Extensions:
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
      const response = await fetch(url, {headers:{'User-Agent':'Retra-Catalog-Builder/1'}});
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

// ---------- Romhack Plaza (catalog only) ----------
// Romhack Plaza (https://romhackplaza.org) hosts ROM hacks and fan translations as patch files,
// which players apply to their own clean base ROM. Its hack pages are rendered client-side, so the
// catalog is built only from the sitemap it publishes for indexers: page address, section,
// platform (slug suffix), and last-modified date. Nothing from the pages themselves (text or
// images) is copied, no files are re-hosted, and every entry is catalog-only, linking to its page.
const PLAZA_SITEMAP = 'https://romhackplaza.org/sitemap.xml';
const PLAZA_SUFFIX = /-(game-boy-advance|game-boy-color|game-boy|gba|gbc|gb)(-romhack|-translation)?(-\d+)?$/;
const PLAZA_PLATFORM = {'game-boy-advance':'GBA', gba:'GBA', 'game-boy-color':'GBC', gbc:'GBC', 'game-boy':'GB', gb:'GB'};
const PLATFORM_NAME = {GB:'Game Boy', GBC:'Game Boy Color', GBA:'Game Boy Advance'};
const LANGUAGES = ['english','spanish','french','german','italian','portuguese','brazilian','dutch','russian','polish','vietnamese',
  'chinese','japanese','korean','indonesian','turkish','arabic','greek','swedish','finnish','norwegian','danish','czech',
  'hungarian','romanian','catalan','ukrainian','hebrew','thai','malay','filipino','tagalog','galician','basque','esperanto'];
function slugTitle(slug){
  return slug.split('-').filter(Boolean).map(word => /^(i{1,3}|iv|vi{0,3}|ix|x)$/i.test(word) ? word.toUpperCase() :
    word.charAt(0).toUpperCase() + word.slice(1)).join(' ').replace(/\bV(\d+) (\d+)\b/g, 'v$1.$2');
}
/** Turn one sitemap entry into a catalog-only game, or null if it is not a GB/GBC/GBA hack or translation. */
function plazaGame(loc, lastmod){
  let url;
  try { url = new URL(loc); } catch (_) { return null; }
  const [section, slug, ...rest] = url.pathname.split('/').filter(Boolean);
  if (rest.length || !['romhacks', 'translations'].includes(section) || !slug) return null;
  const match = slug.match(PLAZA_SUFFIX);
  if (!match || !acceptedUrl(loc)) return null;
  const platform = PLAZA_PLATFORM[match[1]];
  let decoded = slug.slice(0, match.index);
  try { decoded = decodeURIComponent(decoded); } catch (_) { /* keep the raw slug */ }
  // Titles keep letters, digits and hyphens only (drops emoji and stray symbols from slugs).
  const name = decoded.replace(/[^\p{L}\p{N}-]+/gu, '').replace(/-+/g, '-').replace(/^-|-$/g, '');
  const translation = section === 'translations';
  const language = translation ? (name.match(/-([a-z]+)-translation$/) || [])[1] : undefined;
  const title = slugTitle(name.replace(/-(romhack|translation)$/, '')) || slugTitle(slug);
  const id = catalogId(slug);
  if (!id) return null;
  return {
    id, title, platform,
    genre:/pokemon|pokmon/.test(slug) ? 'Pokémon' : undefined,
    gameType:translation ? 'translation' : 'romhack',
    language:LANGUAGES.includes(language) ? language.charAt(0).toUpperCase() + language.slice(1) : undefined,
    updatedAt:Date.parse(lastmod || '') || 0,
    description:`${translation ? 'Fan translation' : 'Fan-made ROM hack'} for ${PLATFORM_NAME[platform]}, listed on Romhack Plaza. ` +
      `Romhack Plaza hosts the patch file: open the website, download the patch, then add it in Library → + together with your own clean ${PLATFORM_NAME[platform]} ROM. Retra applies the patch when the game starts.`,
    website:loc
  };
}
async function plazaCatalogs(){
  const xml = (await fetchRetry(PLAZA_SITEMAP, 'buffer')).toString('utf8');
  const games = [];
  for (const [, block] of xml.matchAll(/<url>([\s\S]*?)<\/url>/g)) {
    const loc = (block.match(/<loc>([^<]+)<\/loc>/) || [])[1];
    const game = loc && plazaGame(loc.trim(), (block.match(/<lastmod>([^<]+)<\/lastmod>/) || [])[1]);
    if (game) games.push(game);
  }
  // The site sometimes lists one hack on several pages ("-2" addresses): keep the newest per title.
  const newest = new Map();
  for (const game of games) {
    const key = `${game.platform}|${game.gameType}|${game.title.toLowerCase()}`;
    if (!newest.has(key) || newest.get(key).updatedAt < game.updatedAt) newest.set(key, game);
  }
  return [...newest.values()];
}
// ---------- Original-game box art for hacks (libretro-thumbnails) ----------
// Same source Retra's ArtworkRepository uses for Library covers. A hack's cover is the box art of the
// game it modifies, matched from its title; entries without a confident match keep generated art.
const BOXART_REPOS = {GB:'Nintendo_-_Game_Boy', GBC:'Nintendo_-_Game_Boy_Color', GBA:'Nintendo_-_Game_Boy_Advance'};
const STOPWORDS = new Set(['version', 'the', 'a', 'an', 'of', 'and', 'no', 'de', 'la']);
const REGION_RANK = ['USA', 'World', 'Europe', 'Japan'];
const FRANCHISES = new Set(['pokemon', 'mario', 'zelda', 'sonic', 'kirby', 'yoshi', 'wario', 'metroid', 'digimon', 'disney', 'nintendo', 'sega', 'bomberman']);
function titleTokens(value){
  return String(value).toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, '').replace(/'/g, '')
    .split(/[^a-z0-9]+/).filter(token => token && !STOPWORDS.has(token));
}
/** Hack tokens plus joined neighbours, so "fire red" also matches "FireRed". */
function hackTokenSet(title){
  const tokens = titleTokens(title);
  const set = new Set(tokens);
  for (let i = 0; i + 1 < tokens.length; i++) set.add(tokens[i] + tokens[i + 1]);
  return set;
}
async function boxartIndex(){
  const index = {};
  const headers = {'User-Agent':'Retra-Catalog-Builder/1', Accept:'application/vnd.github+json'};
  if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  for (const [platform, repo] of Object.entries(BOXART_REPOS)) {
    try {
      const response = await fetch(`https://api.github.com/repos/libretro-thumbnails/${repo}/git/trees/master?recursive=1`, {headers});
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const tree = await response.json();
      // Regular files only: symlinked names would serve link text instead of an image.
      index[platform] = tree.tree.filter(item => item.type === 'blob' && item.mode === '100644' && /^Named_Boxarts\/[^/]+\.png$/.test(item.path))
        .map(item => {
          const name = item.path.slice('Named_Boxarts/'.length, -4);
          const base = name.replace(/\s*\(.*$/, '');
          const regions = (name.match(/\(([^)]*)\)/) || [])[1] || '';
          const rank = REGION_RANK.findIndex(region => regions.split(/,\s*/).includes(region));
          // "Pokemon - Yellow Version - Special Pikachu Edition" also matches as "Pokemon - Yellow Version".
          const segments = base.split(' - ');
          const variants = [base, ...(segments.length >= 3 ? [segments.slice(0, 2).join(' - ')] : [])]
            .map(text => [...new Set(titleTokens(text))]).filter(tokens => tokens.length);
          return {name, variants, rank:rank < 0 ? REGION_RANK.length : rank,
            clean:!/\((Beta|Proto|Demo|Sample|Hack|Unl|Pirate|Kiosk|Aftermarket|Homebrew|Program|Test|Competition)/i.test(name)};
        }).filter(entry => entry.variants.length && entry.clean);
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
/** Best original game for a hack title: every title word present, longest title wins, then region. */
function matchBoxart(title, platform, index){
  const hack = hackTokenSet(title);
  const order = platform === 'GBA' ? ['GBA'] : platform === 'GBC' ? ['GBC', 'GB'] : ['GB', 'GBC'];
  for (const system of order) {
    let best = null;
    for (const entry of index[system] || []) {
      for (const tokens of entry.variants) {
        if (!tokens.every(token => hack.has(token))) continue;
        // A one-word title must be a distinctive game ("Tetris"), not a short word or a whole franchise.
        if (tokens.length === 1 && (tokens[0].length < 5 || FRANCHISES.has(tokens[0]))) continue;
        const score = tokens.length * 10 - entry.rank;
        if (!best || score > best.score) best = {score, entry};
      }
    }
    if (best) return {url:boxartUrl(system, best.entry.name), name:best.entry.name};
  }
  return null;
}

function patchIcon(){
  // Original artwork: two overlapping tiles, a "patch" over a cartridge, in the handheld palette.
  const shades = [[155,188,15],[139,172,15],[48,98,48],[15,56,15]];
  return sample.png(96, 96, (x, y) => {
    const back = x >= 14 && x < 62 && y >= 14 && y < 62;
    const front = x >= 34 && x < 82 && y >= 34 && y < 82;
    const stitch = front && (x === 40 || x === 75 || y === 40 || y === 75) && (x + y) % 6 < 3;
    return stitch ? shades[3] : front ? shades[0] : back ? shades[2] : shades[3];
  });
}

// ---------- Official games (catalog only) ----------
// One entry per licensed GB/GBC/GBA release that has box art in libretro-thumbnails (No-Intro names),
// with developer, publisher, release year and genre from libretro-database. These are catalog entries
// only: no download links. Players add a game to their Library and link their own cartridge dump with
// Import ROM.
const METADAT = 'https://raw.githubusercontent.com/libretro/libretro-database/master/metadat';
const DAT_NAMES = {GB:'Nintendo - Game Boy', GBC:'Nintendo - Game Boy Color', GBA:'Nintendo - Game Boy Advance'};
const artKey = name => name.replace(/[&*/:"<>?\\|]/g, '_').toLowerCase();
async function metadata(platform){
  const fields = {developer:'developer', publisher:'publisher', releaseyear:'releaseyear', genre:'genre'};
  const result = {names:new Map(), official:new Set()};
  // No-Intro: the list of real cartridge releases. Box art for hacks or fan works is not in it.
  try {
    const text = (await fetchRetry(`${METADAT}/no-intro/${encodeURIComponent(DAT_NAMES[platform])}.dat`, 'buffer')).toString('utf8');
    for (const [, name] of text.matchAll(/game \(\s*\n\s*name "([^"]*)"/g)) result.official.add(artKey(name));
  } catch (error) { console.warn(`No-Intro list ${platform} unavailable: ${error.message}`); }
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
// Releases sold only in these regions are translations of English releases ("Blaue Edition", "Edicion Azul").
const LOCALIZED_ONLY = new Set(['Germany', 'France', 'Spain', 'Italy', 'Netherlands', 'Sweden', 'Denmark', 'Norway',
  'Finland', 'Portugal', 'Brazil', 'Greece', 'Poland', 'Russia', 'Korea', 'China', 'Taiwan', 'Hong Kong']);
function officialGames(platform, entries, meta){
  const best = new Map();
  for (const entry of entries) {
    if (meta.official.size && !meta.official.has(artKey(entry.name))) continue;
    if (/\[/.test(entry.name)) continue; // [Hack by …], [T-En by …]
    const regions = ((entry.name.match(/\(([^)]*)\)/) || [])[1] || '').split(/,\s*/);
    if (regions.every(region => LOCALIZED_ONLY.has(region))) continue;
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
    const cover = boxartUrl(platform, entry.name);
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

// ---------- Original artwork (anti-aliased) ----------
const supersample = (fn, size = 4) => (x, y) => {
  const sum = [0, 0, 0];
  for (let i = 0; i < size; i++) for (let j = 0; j < size; j++) {
    const color = fn(x + (i + 0.5) / size, y + (j + 0.5) / size);
    sum[0] += color[0]; sum[1] += color[1]; sum[2] += color[2];
  }
  return sum.map(value => Math.round(value / (size * size)));
};
const inRoundRect = (x, y, x0, y0, x1, y1, r) => {
  if (x < x0 || x > x1 || y < y0 || y > y1) return false;
  const cx = Math.min(Math.max(x, x0 + r), x1 - r), cy = Math.min(Math.max(y, y0 + r), y1 - r);
  return (x - cx) ** 2 + (y - cy) ** 2 <= r * r;
};
const mix = (a, b, t) => a.map((value, i) => value + (b[i] - value) * t);
/** A cartridge on a diagonal gradient; `mark` draws the label symbol ("patch" plus or stripes). */
function cartridgeArt(size, from, to, label, mark){
  const s = size / 192;
  return sample.png(size, size, supersample((px, py) => {
    const x = px / s, y = py / s;
    let color = mix(from, to, (x + y) / 384);
    const body = inRoundRect(x, y, 50, 30, 142, 162, 14) && !(x > 118 && y < 54 && (x - 118) + (54 - y) > 24);
    if (body) color = [244, 244, 245];
    if (body && inRoundRect(x, y, 64, 52, 128, 116, 10)) {
      color = label;
      if (mark(x - 96, y - 84)) color = [255, 255, 255];
    }
    if (body && y > 146 && y < 158 && x > 70 && x < 122 && Math.floor((x - 70) / 6) % 2 === 0) color = mix(from, to, 0.6);
    if (body && inRoundRect(x, y, 70, 126, 122, 134, 4)) color = [212, 212, 216];
    return color;
  }));
}
const plusMark = (x, y) => (Math.abs(x) < 7 && Math.abs(y) < 22) || (Math.abs(y) < 7 && Math.abs(x) < 22);
const stripeMark = (x, y) => Math.abs(y) < 20 && Math.abs(x) < 22 && Math.floor((y + 20) / 8) % 2 === 0;
const LOGOS = {
  plaza:() => cartridgeArt(192, [15, 118, 110], [55, 48, 163], [245, 158, 11], plusMark),
  GB:() => cartridgeArt(192, [107, 114, 128], [31, 41, 55], [132, 204, 22], stripeMark),
  GBC:() => cartridgeArt(192, [168, 85, 247], [76, 29, 149], [236, 72, 153], stripeMark),
  GBA:() => cartridgeArt(192, [79, 70, 229], [30, 27, 75], [56, 189, 248], stripeMark)
};

function readme(base, extensions){
  const url = `${base}index.json`;
  return `# Retra extension repository\n\nCatalog extensions for Retra (extension API 1). Add this URL in Retra under ` +
    `**Browse → Extensions → ⋮ → Extension repos → Add**:\n\n\`\`\`\n${url}\n\`\`\`\n\n## Extensions\n\n` +
    extensions.map(ext => `- **${ext.name}**: ${ext.summary}`).join('\n') +
    `\n\nEverything here is catalog data. Commercial games are listed for your Library only, with no download links. ` +
    `Add a game, then link your own cartridge dump with Import ROM. Romhack Plaza entries link to the page hosting the hack's patch. ` +
    `Apply it to your own clean ROM from Library → +.\n\nCovers come from libretro-thumbnails, and game details from libretro-database.\n\n` +
    `This branch is generated by \`tools/extension-repo/build-repo.cjs\` on \`main\` and refreshed weekly. Do not edit it by hand.\n`;
}

async function build(base, out, {homebrew = false} = {}){
  assert(new URL(base).protocol === 'https:' && base.endsWith('/'), 'Base must be an https URL ending in /');
  fs.mkdirSync(out, {recursive:true});
  const cacheFile = path.join(out, 'hashes.json');
  const cache = fs.existsSync(cacheFile) ? JSON.parse(fs.readFileSync(cacheFile, 'utf8')) : {};
  // Start from an empty tree (keeping git metadata and the ROM hash cache) so removed extensions disappear.
  for (const name of fs.readdirSync(out)) if (!['.git', 'hashes.json'].includes(name)) fs.rmSync(path.join(out, name), {recursive:true, force:true});
  const extensions = [];
  const summaries = [];
  const boxarts = await boxartIndex();

  // Romhack Plaza: hack and translation index plus official game catalogs, in one extension.
  const plaza = await plazaCatalogs();
  for (const game of plaza) {
    const match = matchBoxart(game.title, game.platform, boxarts);
    if (!match || !acceptedUrl(match.url)) continue;
    game.cover = match.url;
    game.description += ` Cover shows the original game (${match.name.replace(/\s*\(.*$/, '')}).`;
  }
  console.log(`Romhack Plaza covers matched: ${plaza.filter(game => game.cover).length} of ${plaza.length}`);
  const plazaDir = path.join(out, 'romhackplaza');
  fs.mkdirSync(plazaDir, {recursive:true});
  fs.writeFileSync(path.join(plazaDir, 'icon.png'), LOGOS.plaza());
  const plazaSources = [];
  for (const platform of PLATFORMS) {
    const ids = new Set();
    const games = plaza.filter(game => game.platform === platform && !ids.has(game.id) && ids.add(game.id))
      .sort((a, b) => a.title.localeCompare(b.title, 'en', {sensitivity:'base'}));
    writeJson(path.join(plazaDir, `${platform.toLowerCase()}.json`), {apiVersion:1, games});
    plazaSources.push({id:platform.toLowerCase(), name:`Romhack Plaza ${platform}`, platforms:[platform], latest:true,
      catalogUrl:`${base}romhackplaza/${platform.toLowerCase()}.json`, icon:`${base}romhackplaza/icon.png`});
    console.log(`Romhack Plaza ${platform}: ${games.length} hacks and translations (catalog only)`);
  }
  for (const platform of PLATFORMS) {
    const games = officialGames(platform, boxarts[platform] || [], await metadata(platform));
    assert(games.length <= 10000, 'API 1 catalogs hold at most 10,000 games');
    const file = `official-${platform.toLowerCase()}`;
    writeJson(path.join(plazaDir, `${file}.json`), {apiVersion:1, games});
    fs.writeFileSync(path.join(plazaDir, `${file}.png`), LOGOS[platform]());
    plazaSources.push({id:file, name:`Official ${platform} Games`, platforms:[platform], latest:true,
      catalogUrl:`${base}romhackplaza/${file}.json`, icon:`${base}romhackplaza/${file}.png`});
    console.log(`Official ${platform}: ${games.length} games, ${games.filter(game => game.developer || game.publisher).length} with developer/publisher`);
  }
  // versionCode 2: official game sources added (installed copies show "Update available").
  const plazaSha = writeJson(path.join(plazaDir, 'extension.json'), {apiVersion:1, id:'org.retra.romhackplaza', versionCode:2, sources:plazaSources});
  extensions.push({
    id:'org.retra.romhackplaza', name:'Romhack Plaza', publisher:'Retra contributors (index of romhackplaza.org and official releases)',
    versionCode:2, versionName:'2.0', format:'retra.catalog', apiMin:1, apiMax:1, platforms:PLATFORMS,
    packageUrl:`${base}romhackplaza/extension.json`, sha256:plazaSha, icon:`${base}romhackplaza/icon.png`
  });
  summaries.push({name:'Romhack Plaza', summary:'GB, GBC and GBA ROM hacks and fan translations listed on romhackplaza.org, plus catalogs of official GB, GBC and GBA games with box art.'});

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
module.exports = {build, normalizeLicense, toGame, acceptedUrl, plazaGame, matchBoxart, titleTokens, displayTitle, officialGames, LICENSE};
