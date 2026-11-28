'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {createHash} = require('node:crypto');
const {build, SITES, WEB_ONLY, websiteGame, idroidGame, gamebrewGame, feuGame, siteDate, plainText, officialGames, EXTENSION_VERSION, LEGACY_BUNDLE} = require('../tools/extension-repo/build-repo.cjs');
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

const gamebrewPage = (pageid, title, type = 'Game Hacks') => ({pageid, title: title + ' GBA', fullurl:'https://www.gamebrew.org/wiki/' + title.replace(/ /g, '_') + '_GBA',
  thumbnail:{source:'https://www.gamebrew.org/images/2/26/' + pageid + '.png'},
  revisions:[{slots:{main:{content:'{{#seo:\n|title=x\n}}\n{{Infobox GBA Homebrews\n|title=' + title + '\n|description=A [[Fire Red]] hack with \'\'new\'\' maps.\n' +
    '|author=[https://example.org Skeli]\n|lastupdated=2021/11/18\n|type=' + type + '\n|version=1.1\n}}\nBody text.'}}}]});
const feuTopic = (id, tag, extra = {}) => ({id, slug:'hack-' + id, title:'Hack ' + id, tags:[{name:'complete_project'}, {name:tag}],
  image_url:'https://feuniverse.us/uploads/default/original/' + id + '.png', created_at:'2023-01-05T00:41:26.653Z',
  last_posted_at:'2026-11-25T09:26:24.509Z', pinned:false, ...extra});

test('GameBrew wiki pages become GBA catalog games from their infobox', () => {
  const game = gamebrewGame(gamebrewPage(21271, 'F-Zero - Climax', 'Translations'));
  assert.equal(game.id, '21271');
  assert.equal(game.title, 'F-Zero - Climax');
  assert.equal(game.platform, 'GBA');
  assert.equal(game.developer, 'Skeli');
  assert.equal(game.genre, 'Translations');
  assert.equal(game.year, '2021');
  assert.equal(game.updatedAt, Date.UTC(2021, 10, 18));
  assert.equal(game.description, 'A Fire Red hack with new maps. Version 1.1.');
  assert.equal(game.cover, 'https://www.gamebrew.org/images/2/26/21271.png');
  assert.equal(game.website, 'https://www.gamebrew.org/wiki/F-Zero_-_Climax_GBA');
  assert.equal(game.download, undefined);
  assert.equal(gamebrewGame({...gamebrewPage(1, 'X'), fullurl:'https://attacker.example/wiki/X'}), null);
  assert.equal(gamebrewGame({...gamebrewPage(1, 'X'), revisions:[{slots:{main:{content:'No infobox here.'}}}]}), null);
});

test('FE Universe completed projects on a GBA base become Fire Emblem catalog games', () => {
  const game = feuGame(feuTopic(18883, 'fe8_base'));
  assert.equal(game.id, '18883');
  assert.equal(game.platform, 'GBA');
  assert.equal(game.genre, 'Fire Emblem');
  assert.equal(game.website, 'https://feuniverse.us/t/hack-18883/18883');
  assert.equal(game.cover, 'https://feuniverse.us/uploads/default/original/18883.png');
  assert.match(game.description, /^Base game: Fire Emblem: The Sacred Stones\./);
  assert.equal(game.updatedAt, Date.parse('2026-11-25T09:26:24.509Z'));
  assert.equal(feuGame(feuTopic(2, 'snesfe')), null, 'SNES hacks are skipped');
  assert.equal(feuGame(feuTopic(3, 'fe7_base', {pinned:true})), null, 'pinned guides are skipped');
  assert.equal(feuGame(feuTopic(4, 'fe6_base', {image_url:'https://attacker.example/x.png'})).cover, undefined);
  assert.equal(feuGame(feuTopic(5, 'fe6_base', {slug:'../x'})), null);
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

test('default repository publishes one extension per website and no all-in-one bundle', async () => {
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
    if (url.href.startsWith('https://www.gamebrew.org/api.php')) {
      if (url.searchParams.get('list') === 'categorymembers') return Response.json({query:{categorymembers:[{pageid:21271}, {pageid:21316}]}});
      return Response.json({query:{pages:url.searchParams.get('pageids').split('|').map(id => gamebrewPage(Number(id), 'Hack ' + id))}});
    }
    if (url.hostname === 'feuniverse.us') {
      const page = Number(url.searchParams.get('page'));
      return Response.json({topic_list:{topics:page ? [feuTopic(30, 'fe7_base')] : [feuTopic(10, 'fe8_base'), feuTopic(20, 'snesfe')],
        more_topics_url:page ? undefined : '/tags/c/projects/17/complete_project?page=1'}});
    }
    if (url.hostname === 'api.github.com') return Response.json({tree:[{type:'blob', mode:'100644', path:'Named_Boxarts/Harvest Moon (USA).png'}]});
    if (url.pathname.endsWith('.dat')) return new Response('game (\n name "Harvest Moon (USA)"\n rom ( name "Harvest Moon (USA).gb" )\n)\n');
    if (url.pathname.endsWith('/categories')) return Response.json(url.searchParams.get('slug').split(',')
      .map((slug, i) => ({id:i + 1, count:i ? 1 : 101, slug})));
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
    // One extension per website, sorted by name; the Retra Sources bundle is gone.
    assert.deepEqual(index.extensions.map(ext => ext.name), ['FE Universe', 'GameBrew', 'Hackdex', 'Operation iDroid', 'PokéCoders',
      'PokéCommunity', 'PokéHarbor', 'PokéMerald', 'PokeMundo', 'RHDI', 'Romhacking.net', 'Roms Games']);
    assert.ok(!index.extensions.some(ext => ext.id === LEGACY_BUNDLE || ext.name === 'Retra Sources'));
    assert.equal(fs.existsSync(path.join(dir, 'sources/extension.json')), false);
    const manifests = new Map();
    for (const ext of index.extensions) {
      assert.ok(ext.id.startsWith('org.retra.'), ext.id);
      assert.equal(ext.versionCode, EXTENSION_VERSION.versionCode);
      assert.doesNotMatch([ext.name, ext.publisher, ext.packageUrl, ext.icon].join(' '), /romhack ?plaza/i);
      const file = ext.packageUrl.replace('https://example.org/repo/', '');
      assert.match(file, /^packages\/[a-z]+\.json$/);
      assert.equal(ext.sha256, createHash('sha256').update(read(file)).digest('hex'));
      const manifest = JSON.parse(read(file));
      assert.equal(manifest.id, ext.id);
      // Extension platforms are exactly the platforms of its sources (the app requires sources to fit).
      assert.deepEqual(ext.platforms, ['GB', 'GBC', 'GBA'].filter(p => manifest.sources.some(source => source.platforms.includes(p))));
      manifests.set(ext.id, manifest.sources.map(source => source.id));
    }
    // Source IDs are unchanged, so Library games and pins saved from the bundle resolve to the per-site source.
    assert.deepEqual(manifests.get('org.retra.operationidroid'), ['operationidroid-gba', 'operationidroid-gbc']);
    assert.deepEqual(manifests.get('org.retra.pokeharbor'), ['pokeharbor-gba', 'pokeharbor-gbc']);
    assert.deepEqual(manifests.get('org.retra.romsgames'), ['official-gb', 'official-gbc', 'official-gba']);
    assert.deepEqual(manifests.get('org.retra.hackdex'), ['hackdex']);
    assert.equal(index.extensions.find(ext => ext.id === 'org.retra.operationidroid').icon, 'https://example.org/repo/sources/operationidroid.png');
    const sources = index.extensions.flatMap(ext => JSON.parse(read(ext.packageUrl.replace('https://example.org/repo/', ''))).sources);
    assert.deepEqual(sources.map(source => source.id).sort(), ['pokeharbor-gba', 'pokeharbor-gbc', 'pokemundo-gba', 'pokemundo-gbc',
      'pokecoders-gba', 'pokecoders-gbc', 'pokemerald-gba', 'pokemerald-gbc', 'operationidroid-gba', 'operationidroid-gbc',
      'gamebrew-gba', 'feuniverse-gba', 'romhacking', 'hackdex', 'rhdi', 'pokecommunity', 'official-gb', 'official-gbc', 'official-gba'].sort());
    const readme = read('README.md').toString();
    assert.match(readme, /Each website is its own extension/);
    assert.match(readme, /\*\*Operation iDroid\*\*: GBC, GBA: /);
    assert.ok(sources.every(source => !/romhack ?plaza/i.test(source.name + source.catalogUrl + source.icon)));
    assert.equal(fs.existsSync(path.join(dir, 'homebrewhub')), false);
    assert.equal(fs.existsSync(path.join(dir, 'romhackplaza')), false);
    assert.ok(!requests.some(url => /romhackplaza|romhacking\.net|hackdex|pokecommunity|romhack\.ing/.test(url)), 'website-only sites are never fetched');
    const hosts = new Set(sources.map(source => new URL(source.website).hostname));
    for (const host of ['www.romsgames.net', 'www.pokeharbor.com', 'pokemundo.com', 'www.pokemoncoders.com', 'pokemerald.com', 'www.romhacking.net',
      'www.hackdex.app', 'operationidroid.com', 'www.gamebrew.org', 'feuniverse.us', 'romhack.ing', 'www.pokecommunity.com']) assert.ok(hosts.has(host), host);
    // Every source shows its own website's logo, not a generated placeholder; a site's sources share one logo.
    for (const site of WEB_ONLY) assert.ok(read('sources/' + site.id + '.png').equals(fs.readFileSync(path.join(__dirname, '../tools/extension-repo/icons/' + site.logo))), site.id);
    assert.ok(read('sources/pokemerald.png').equals(fs.readFileSync(path.join(__dirname, '../tools/extension-repo/icons/pokemerald.png'))));
    assert.equal(sources.find(source => source.id === 'pokemerald-gba').icon, sources.find(source => source.id === 'pokemerald-gbc').icon);
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
    assert.deepEqual(JSON.parse(read('sources/gamebrew-gba.json')).games.map(game => game.id), ['21271', '21316']);
    assert.deepEqual(JSON.parse(read('sources/feuniverse-gba.json')).games.map(game => game.id), ['10', '30']);
    assert.equal(JSON.parse(read('sources/pokeharbor-gba.json')).games.length, 101);
    assert.ok(requests.some(url => url.includes('page=2')));
    assert.ok(JSON.parse(read('sources/official-gba.json')).games[0].website.includes('/search/?q=Harvest%20Moon'));
  } finally {
    global.fetch = originalFetch;
    fs.rmSync(dir, {recursive:true, force:true});
  }
});

test('a site that is down during a rebuild keeps its last published catalog', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'retra-sites-down-'));
  const originalFetch = global.fetch;
  const png = require('../samples/catalog-extension/build-fixture.cjs').png(8, 8, () => [20, 100, 140]);
  const kept = {apiVersion:1, games:[{id:'7', title:'Kept Hack', platform:'GBA', gameType:'romhack', cover:'https://pokemundo.com/c.png', website:'https://pokemundo.com/en/kept/'}]};
  fs.mkdirSync(path.join(dir, 'sources'));
  fs.writeFileSync(path.join(dir, 'sources/pokemundo-gba.json'), JSON.stringify(kept));
  fs.writeFileSync(path.join(dir, 'sources/pokemundo.png'), png);
  global.fetch = async address => {
    const url = new URL(address);
    if (url.hostname === 'pokemundo.com') throw new Error('fetch failed');
    if (url.pathname.endsWith('.png')) return new Response(png);
    if (url.href === 'https://operationidroid.com/sitemap.xml') return new Response('<urlset><url><loc>https://operationidroid.com/hacks/unbound</loc></url></urlset>');
    if (url.hostname === 'operationidroid.com') return new Response(idroidPage('Pokémon Unbound', 'Game Boy Advance'));
    if (url.href.startsWith('https://www.gamebrew.org/api.php')) {
      if (url.searchParams.get('list') === 'categorymembers') return Response.json({query:{categorymembers:[{pageid:1}]}});
      return Response.json({query:{pages:[gamebrewPage(1, 'Hack')]}});
    }
    if (url.hostname === 'feuniverse.us') return Response.json({topic_list:{topics:[feuTopic(10, 'fe8_base')]}});
    if (url.hostname === 'api.github.com') return Response.json({tree:[]});
    if (url.pathname.endsWith('.dat')) return new Response('game (\n name "Harvest Moon (USA)"\n rom ( name "Harvest Moon (USA).gb" )\n)\n');
    if (url.pathname.endsWith('/categories')) return Response.json(url.searchParams.get('slug').split(',').map((slug, i) => ({id:i + 1, count:1, slug})));
    if (url.pathname.endsWith('/posts')) return Response.json([{id:1, title:{rendered:'Quest'}, link:url.origin + '/q/'}]);
    throw new Error('Unexpected request: ' + address);
  };
  try {
    await build('https://example.org/repo/', dir);
    const sources = JSON.parse(fs.readFileSync(path.join(dir, 'packages/pokemundo.json'), 'utf8')).sources.map(source => source.id);
    assert.ok(sources.includes('pokemundo-gba'), 'the down site is still listed');
    assert.ok(!sources.includes('pokemundo-gbc'), 'a platform with no previous catalog is skipped');
    assert.deepEqual(JSON.parse(fs.readFileSync(path.join(dir, 'sources/pokemundo-gba.json'), 'utf8')).games.map(game => game.id), ['7']);
    const listed = fs.readdirSync(path.join(dir, 'packages'))
      .flatMap(file => JSON.parse(fs.readFileSync(path.join(dir, 'packages', file), 'utf8')).sources.map(source => source.id));
    assert.ok(listed.includes('gamebrew-gba') && listed.includes('feuniverse-gba'), 'other sites still publish');
  } finally {
    global.fetch = originalFetch;
    fs.rmSync(dir, {recursive:true, force:true});
  }
});
