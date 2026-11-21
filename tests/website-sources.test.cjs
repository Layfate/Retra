'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {createHash} = require('node:crypto');
const {build, SITES, WEB_ONLY, websiteGame, idroidGame, siteDate, plainText, officialGames} = require('../tools/extension-repo/build-repo.cjs');
const {gameDownloadAction, filterSources, extensionSections, emptyCatalogAction} = require('../app/src/main/assets/retra/browse-ui.js');

const idroidPage = (name, platform, image = '/images/cover.jpg') => '<meta property="og:title" content="' + name +
  ' Download 2026 — Pokemon Fire Red ROM Hack"/><script type="application/ld+json">' + JSON.stringify({'@context':'https://schema.org',
  '@graph':[{'@type':'VideoGame', name, gamePlatform:platform, author:{'@type':'Person', name:'Skeli'}, version:'2.1',
    dateModified:'11/08/22', image, description:'A <b>new</b> region.'}, {'@type':'BreadcrumbList'}]}) + '</script>';

test('website actions keep catalog browsing distinct from verified direct downloads', () => {
  assert.equal(filterSources([{name:'PokéHarbor GBA', platforms:['GBA']}], 'pokeharbor', '', 'name').length, 1);
  assert.equal(extensionSections([{meta:{name:'PokéHarbor'}, repo:{name:'Retra'}, status:'Available'}], 'pokeharbor').length, 1);
  assert.equal(gameDownloadAction({website:'https://example.org/game/'}), 'website');
  assert.equal(gameDownloadAction({website:'javascript:alert(1)'}), '');
  assert.equal(gameDownloadAction({}), '');
  assert.equal(gameDownloadAction({download:{authorized:true, license:'MIT'}}), 'direct');
  assert.equal(gameDownloadAction({download:{authorized:true, license:'proprietary'}}), '');
});

test('WordPress entries use actual artwork and decode titles without copying HTML', () => {
  const post = {id:42, link:'https://www.pokeharbor.com/2026/11/pokemon-quest/',
    title:{rendered:'Pok&#233;mon &amp; Friends &#8217; <b>Quest</b>'}, excerpt:{rendered:'<p>A new adventure.</p>'},
    modified_gmt:'2026-11-12T04:00:00',
    _embedded:{'wp:featuredmedia':[{source_url:'https://www.pokeharbor.com/cover.png'}]}};
  const game = websiteGame(post, 'GBA', SITES[0]);
  assert.equal(game.title, 'Pokémon & Friends ’ Quest');
  assert.equal(game.description, 'A new adventure.');
  assert.equal(game.cover, 'https://www.pokeharbor.com/cover.png');
  assert.equal(game.updatedAt, Date.parse('2026-11-12T04:00:00Z'));
  assert.equal(game.download, undefined);
  assert.equal(websiteGame({...post, link:'https://attacker.example/game'}, 'GBA', SITES[0]), null);
  assert.equal(websiteGame({...post, id:'bad'}, 'GBA', SITES[0]), null);
  assert.equal(plainText('Bad &#99999999; entity &nbsp; text'), 'Bad entity text');
});

test('Operation iDroid pages become GB/GBC/GBA catalog games from their JSON-LD', () => {
  const url = 'https://operationidroid.com/hacks/pokemon-unbound';
  const game = idroidGame(idroidPage('Pokémon Unbound', 'Game Boy Advance'), url);
  assert.equal(game.id, 'pokemon-unbound');
  assert.equal(game.title, 'Pokémon Unbound');
  assert.equal(game.platform, 'GBA');
  assert.equal(game.developer, 'Skeli');
  assert.equal(game.cover, 'https://operationidroid.com/images/cover.jpg');
  assert.equal(game.website, url);
  assert.equal(game.updatedAt, Date.UTC(2022, 10, 8));
  assert.match(game.description, /^Base game: Pokémon Fire Red\. Version 2\.1\. A new region\.$/);
  assert.equal(game.download, undefined);
  assert.equal(idroidGame(idroidPage('Crystal', 'Game Boy Color'), 'https://operationidroid.com/hacks/crystal').platform, 'GBC');
  assert.equal(idroidGame(idroidPage('Kaizo', 'Nintendo DS'), 'https://operationidroid.com/hacks/kaizo'), null);
  assert.equal(idroidGame(idroidPage('Off site', 'Game Boy Advance'), 'https://attacker.example/hacks/x'), null);
  assert.equal(idroidGame('<html></html>', url), null);
  assert.equal(siteDate('06/12/26'), Date.UTC(2026, 5, 12));
  assert.equal(siteDate('nonsense'), 0);
});

test('empty catalogs of website sources offer the website instead of a dead end', () => {
  const hackdex = {name:'Hackdex', website:'https://www.hackdex.app/'};
  assert.equal(emptyCatalogAction(hackdex, '', 0, 0).button, 'Open Hackdex');
  assert.equal(emptyCatalogAction({...hackdex, searchUrl:'https://www.hackdex.app/?q={query}'}, 'zelda', 1, 0).button, 'Search Hackdex for "zelda"');
  assert.equal(emptyCatalogAction(hackdex, 'zelda', 1, 0), null);
  assert.equal(emptyCatalogAction(hackdex, '', 0, 4), null);
  assert.equal(emptyCatalogAction({name:'Bad', website:'javascript:alert(1)'}, '', 0, 0), null);
});

test('official release lists include games missing box art and regional releases', () => {
  const name = 'Harvest Moon (Japan)';
  const meta = {official:new Set([name.toLowerCase()]), releases:[name], names:new Map(),
    developer:new Map(), publisher:new Map(), releaseyear:new Map(), genre:new Map()};
  const games = officialGames('GB', [], meta);
  assert.equal(games.length, 1);
  assert.equal(games[0].title, 'Harvest Moon');
  assert.equal(games[0].cover, undefined);
});

test('default repository publishes Retra Sources with the requested websites and no Romhack Plaza', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'retra-sites-'));
  const originalFetch = global.fetch;
  const png = require('../samples/catalog-extension/build-fixture.cjs').png(8, 8, () => [20, 100, 140]);
  const requests = [];
  global.fetch = async address => {
    const url = new URL(address);
    requests.push(url.href);
    if (url.pathname.endsWith('.png')) return new Response(png);
    if (url.href === 'https://operationidroid.com/sitemap.xml') return new Response('<urlset>' + ['unbound', 'crystal', 'kaizo', 'unbound']
      .map(slug => '<url><loc>https://operationidroid.com/hacks/' + slug + '</loc></url>').join('') + '<url><loc>https://operationidroid.com/guides</loc></url></urlset>');
    if (url.hostname === 'operationidroid.com' && url.pathname.startsWith('/hacks/')) {
      const slug = url.pathname.split('/').pop();
      return new Response(idroidPage('Pokémon ' + slug, {unbound:'Game Boy Advance', crystal:'Game Boy Color', kaizo:'Nintendo DS'}[slug]));
    }
    if (url.hostname === 'api.github.com') return Response.json({tree:[{type:'blob', mode:'100644', path:'Named_Boxarts/Harvest Moon (USA).png'}]});
    if (url.pathname.endsWith('.dat')) return new Response('game (\n name "Harvest Moon (USA)"\n rom ( name "Harvest Moon (USA).gb" )\n)\n');
    if (url.pathname.endsWith('/categories')) return Response.json([{id:1, count:101, slug:url.searchParams.get('slug').split(',')[0]},
      {id:2, count:1, slug:'gbc'}]);
    if (url.pathname.endsWith('/posts')) {
      const page = Number(url.searchParams.get('page'));
      const count = url.searchParams.get('categories') === '1' && page === 1 ? 100 : 1;
      return Response.json(Array.from({length:count}, (_, i) => ({id:(page - 1) * 100 + i + 1,
        title:{rendered:'Pokémon Quest ' + i}, link:url.origin + '/game-' + i + '/',
        _embedded:{'wp:featuredmedia':[{source_url:url.origin + '/cover.png'}]}})));
    }
    throw new Error('Unexpected request: ' + address);
  };
  try {
    const read = file => fs.readFileSync(path.join(dir, file));
    fs.mkdirSync(path.join(dir, 'homebrewhub'));
    fs.writeFileSync(path.join(dir, 'homebrewhub/old.json'), '{}');
    fs.mkdirSync(path.join(dir, 'romhackplaza'));
    fs.writeFileSync(path.join(dir, 'romhackplaza/gb.json'), '{}');
    await build('https://example.org/repo/', dir);
    const index = JSON.parse(read('index.json'));
    assert.equal(index.extensions.length, 1);
    const ext = index.extensions[0];
    // Same extension ID, so installed copies update in place and saved source keys keep working.
    assert.equal(ext.id, 'org.retra.romhackplaza');
    assert.equal(ext.versionCode, 4);
    assert.equal(ext.name, 'Retra Sources');
    assert.doesNotMatch([ext.name, ext.publisher, ext.packageUrl, ext.icon].join(' '), /romhack ?plaza/i);
    assert.equal(ext.sha256, createHash('sha256').update(read('sources/extension.json')).digest('hex'));
    const sources = JSON.parse(read('sources/extension.json')).sources;
    assert.deepEqual(sources.map(source => source.id), ['pokeharbor-gba', 'pokeharbor-gbc', 'operationidroid-gba', 'operationidroid-gbc',
      'romhacking', 'hackdex', 'official-gb', 'official-gbc', 'official-gba']);
    assert.ok(sources.every(source => !/romhack ?plaza/i.test(source.name + source.catalogUrl + source.icon)));
    assert.equal(fs.existsSync(path.join(dir, 'homebrewhub')), false);
    assert.equal(fs.existsSync(path.join(dir, 'romhackplaza')), false);
    assert.ok(!requests.some(url => /pokemundo|romhackplaza|romhacking\.net|hackdex/.test(url)), 'bot-protected sites are never fetched');
    const hosts = new Set(sources.map(source => new URL(source.website).hostname));
    for (const host of ['www.romsgames.net', 'www.pokeharbor.com', 'www.romhacking.net', 'www.hackdex.app', 'operationidroid.com']) assert.ok(hosts.has(host), host);
    for (const source of sources) {
      if (source.searchUrl) assert.ok(source.searchUrl.includes('{query}'));
      assert.ok(source.website.startsWith('https://'));
      assert.equal(read(source.icon.replace('https://example.org/repo/', '')).readUInt32BE(0), 0x89504e47);
      const catalog = JSON.parse(read(source.catalogUrl.replace('https://example.org/repo/', '')));
      assert.ok(catalog.games.every(game => game.cover && game.website && !game.download));
      assert.equal(catalog.games.length === 0, WEB_ONLY.some(site => site.id === source.id), source.id);
    }
    assert.equal(sources.find(source => source.id === 'romhacking').searchUrl, 'https://www.romhacking.net/?page=hacks&title={query}&hacksearch=Go');
    assert.deepEqual(JSON.parse(read('sources/operationidroid-gba.json')).games.map(game => game.id), ['unbound']);
    assert.deepEqual(JSON.parse(read('sources/operationidroid-gbc.json')).games.map(game => game.id), ['crystal']);
    assert.equal(JSON.parse(read('sources/pokeharbor-gba.json')).games.length, 101);
    assert.ok(requests.some(url => url.includes('page=2')));
    assert.ok(JSON.parse(read('sources/official-gba.json')).games[0].website.includes('/search/?q=Harvest%20Moon'));
  } finally {
    global.fetch = originalFetch;
    fs.rmSync(dir, {recursive:true, force:true});
  }
});
