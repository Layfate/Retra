// Builds the Retra extension repository (Retra extension API 1).
//
//   node tools/extension-repo/build-repo.cjs <https-base-url/> <output-dir>
//
// The default extension bundles PokéHarbor, PokeMundo and Roms Games sources.
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
    categories:{GBA:'hack-roms-pokemon-gba', GBC:'gbc'}}
];
const ROMSGAMES = {name:'Roms Games', website:'https://www.romsgames.net/',
  searchUrl:'https://www.romsgames.net/search/?q={query}', icon:'https://www.romsgames.net/image/apple-touch-icon.png'};
function plainText(value){
  return String(value || '').replace(/<[^>]*>/g, ' ').replace(/&(#x[0-9a-f]+|#\d+|amp|quot|apos|lt|gt|nbsp|eacute);/gi, (all, entity) => {
    if (entity[0] === '#') {
      const code = entity[1].toLowerCase() === 'x' ? parseInt(entity.slice(2), 16) : Number(entity.slice(1));
      return code > 0 && code <= 0x10ffff && !(code >= 0xd800 && code <= 0xdfff) ? String.fromCodePoint(code) : '';
    }
    return {amp:'&', quot:'"', apos:"'", lt:'<', gt:'>', nbsp:' ', eacute:'é'}[entity.toLowerCase()];
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
    for (let page = 1; page <= Math.ceil(category.count / 100); page++) {
      const query = new URLSearchParams({categories:category.id, per_page:100, page, orderby:'id', order:'asc',
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
// ---------- Box art (libretro-thumbnails, also used by ArtworkRepository) ----------
const BOXART_REPOS = {GB:'Nintendo_-_Game_Boy', GBC:'Nintendo_-_Game_Boy_Color', GBA:'Nintendo_-_Game_Boy_Advance'};
const REGION_RANK = ['USA', 'World', 'Europe', 'Japan'];
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

function readme(base, extensions){
  const url = `${base}index.json`;
  return `# Retra extension repository\n\nCatalog extensions for Retra (extension API 1). Add this URL in Retra under ` +
    `**Browse → Extensions → ⋮ → Extension repos → Add**:\n\n\`\`\`\n${url}\n\`\`\`\n\n## Extensions\n\n` +
    extensions.map(ext => `- **${ext.name}**: ${ext.summary}`).join('\n') +
    '\n\nUpdate the former Romhack Plaza extension to version 3.0 to get these sources. The default index contains one extension; the two homebrew extensions are omitted.\n\n' +
    'Search the installed catalogs in Retra. Use Download on website on a game, or Search website in the source menu, to browse the original site inside Retra. ' +
    'Supported ROM, ZIP and patch downloads can be imported into Library. Patches require your own compatible base ROM. ' +
    'Website downloads require the updated Retra app; older apps can still browse the catalogs.\n\n' +
    'Official games come from the No-Intro release lists in libretro-database, with available box art from libretro-thumbnails. ' +
    'Roms Games entries open a title search, so availability depends on the website. Hack titles and covers come from the public WordPress APIs. ' +
    'Catalogs refresh weekly; the in-app website search can find newer entries. Files are not re-hosted, and commercial downloads are not labeled as openly licensed.\n\n' +
    `These files are generated by \`tools/extension-repo/build-repo.cjs\` in \`extensions/\` on \`main\` and refreshed weekly. Do not edit them by hand.\n`;
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

  // Keep the installed extension identity and official source IDs so saved Library entries still work.
  const dir = path.join(out, 'romhackplaza');
  fs.mkdirSync(dir, {recursive:true});
  const sources = [];
  for (const site of SITES) {
    const iconBytes = await fetchRetry(site.icon, 'buffer');
    fs.writeFileSync(path.join(dir, site.id + '.png'), iconBytes);
    for (const {platform, games} of await websiteCatalogs(site)) {
      const id = site.id + '-' + platform.toLowerCase();
      writeJson(path.join(dir, id + '.json'), {apiVersion:1, games});
      sources.push({id, name:site.name + ' ' + platform, platforms:[platform], latest:true,
        catalogUrl:base + 'romhackplaza/' + id + '.json', icon:base + 'romhackplaza/' + site.id + '.png',
        website:site.website, searchUrl:site.searchUrl});
      console.log(site.name + ' ' + platform + ': ' + games.length + ' games, ' + games.filter(game => game.cover).length + ' covers');
    }
  }
  fs.writeFileSync(path.join(dir, 'romsgames.png'), await fetchRetry(ROMSGAMES.icon, 'buffer'));
  for (const platform of PLATFORMS) {
    const games = officialGames(platform, boxarts[platform] || [], await metadata(platform));
    assert(games.length > 0 && games.length <= 10000, 'Official catalog size invalid');
    const file = 'official-' + platform.toLowerCase();
    for (const game of games) game.website = ROMSGAMES.searchUrl.replace('{query}', encodeURIComponent(game.title));
    writeJson(path.join(dir, file + '.json'), {apiVersion:1, games});
    sources.push({id:file, name:ROMSGAMES.name + ' ' + platform, platforms:[platform], latest:true,
      catalogUrl:base + 'romhackplaza/' + file + '.json', icon:base + 'romhackplaza/romsgames.png',
      website:ROMSGAMES.website + 'roms/' + ({GB:'gameboy', GBC:'gameboy-color', GBA:'gameboy-advance'})[platform] + '/',
      searchUrl:ROMSGAMES.searchUrl});
    console.log('Official ' + platform + ': ' + games.length + ' games, ' + games.filter(game => game.cover).length + ' covers');
  }
  const sha256 = writeJson(path.join(dir, 'extension.json'), {apiVersion:1, id:'org.retra.romhackplaza', versionCode:3, sources});
  extensions.push({id:'org.retra.romhackplaza', name:'PokéHarbor · PokeMundo · Roms Games',
    publisher:'Retra contributors (independent website catalogs)', versionCode:3, versionName:'3.0',
    format:'retra.catalog', apiMin:1, apiMax:1, platforms:PLATFORMS,
    packageUrl:base + 'romhackplaza/extension.json', sha256, icon:base + 'romhackplaza/pokeharbor.png'});
  summaries.push({name:'PokéHarbor · PokeMundo · Roms Games', summary:'Website-named sources with searchable ROM hacks, official GB/GBC/GBA releases and cover images.'});

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
module.exports = {build, normalizeLicense, toGame, acceptedUrl, SITES, ROMSGAMES, plainText, websiteGame, websiteCatalogs, displayTitle, officialGames, LICENSE};
