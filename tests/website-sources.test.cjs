'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {createHash} = require('node:crypto');
const {build, SITES, websiteGame, plainText, officialGames} = require('../tools/extension-repo/build-repo.cjs');
const {gameDownloadAction, filterSources, extensionSections} = require('../app/src/main/assets/retra/browse-ui.js');

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

test('official release lists include games missing box art and regional releases', () => {
  const name = 'Harvest Moon (Japan)';
  const meta = {official:new Set([name.toLowerCase()]), releases:[name], names:new Map(),
    developer:new Map(), publisher:new Map(), releaseyear:new Map(), genre:new Map()};
  const games = officialGames('GB', [], meta);
  assert.equal(games.length, 1);
  assert.equal(games[0].title, 'Harvest Moon');
  assert.equal(games[0].cover, undefined);
});

test('default repository publishes one upgrade with named sources, logos, covers and website search', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'retra-sites-'));
  const originalFetch = global.fetch;
  const png = require('../samples/catalog-extension/build-fixture.cjs').png(8, 8, () => [20, 100, 140]);
  const requests = [];
  global.fetch = async address => {
    const url = new URL(address);
    requests.push(url.href);
    if (url.pathname.endsWith('.png')) return new Response(png);
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
    await build('https://example.org/repo/', dir);
    const index = JSON.parse(read('index.json'));
    assert.equal(index.extensions.length, 1);
    const ext = index.extensions[0];
    assert.equal(ext.id, 'org.retra.romhackplaza');
    assert.equal(ext.versionCode, 3);
    assert.equal(ext.name, 'PokéHarbor · PokeMundo · Roms Games');
    assert.equal(ext.sha256, createHash('sha256').update(read('romhackplaza/extension.json')).digest('hex'));
    const sources = JSON.parse(read('romhackplaza/extension.json')).sources;
    assert.equal(sources.length, 7);
    assert.equal(fs.existsSync(path.join(dir, 'homebrewhub')), false);
    for (const source of sources) {
      assert.ok(source.searchUrl.includes('{query}'));
      assert.ok(source.website.startsWith('https://'));
      assert.equal(read(source.icon.replace('https://example.org/repo/', '')).readUInt32BE(0), 0x89504e47);
      const catalog = JSON.parse(read(source.catalogUrl.replace('https://example.org/repo/', '')));
      assert.ok(catalog.games.every(game => game.cover && game.website && !game.download));
    }
    assert.equal(JSON.parse(read('romhackplaza/pokeharbor-gba.json')).games.length, 101);
    assert.ok(requests.some(url => url.includes('page=2')));
    assert.ok(JSON.parse(read('romhackplaza/official-gba.json')).games[0].website.includes('/search/?q=Harvest%20Moon'));
  } finally {
    global.fetch = originalFetch;
    fs.rmSync(dir, {recursive:true, force:true});
  }
});
