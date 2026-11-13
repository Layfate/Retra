const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {filterSources, extensionStatus, sourceSections, extensionRows, extensionSections, pendingUpdates, isAuthorizedDownload} = require('../app/src/main/assets/retra/browse-ui.js');
const root = path.resolve(__dirname,'..');
const html = fs.readFileSync(path.join(root,'app/src/main/assets/retra/index.html'),'utf8');

test('bottom navigation is Library, History, Browse, More; Library import stays mounted', () => {
  const nav = html.match(/<nav class="bottom-nav">([\s\S]*?)<\/nav>/)[1];
  assert.deepEqual([...nav.matchAll(/data-page="([^"]+)"/g)].map(row => row[1]),['libraryPage','historyPage','browsePage','morePage']);
  assert.match(html,/id="addRomBtn"/); assert.match(html,/id="romFileInput"/);
  const css = fs.readFileSync(path.join(root,'app/src/main/assets/retra/style-core.css'),'utf8');
  assert.match(css,/\.bottom-nav\{[^}]*grid-template-columns:repeat\(4,1fr\)/);
  for (const id of ['browsePage','browseHeader','repositoriesPage','catalogPage','catalogDetailsPage']) assert.match(html,new RegExp(`id="${id}"`));
});

test('source search, platform filters, and stable sorts compose without mutating installed sources', () => {
  const rows = [{name:'Zelda demos',platforms:['GB'],pinned:false},{name:'Arcade',platforms:['GB','GBA'],pinned:true},{name:'Action',platforms:['GBA'],pinned:false}];
  assert.deepEqual(filterSources(rows,'a','GBA','name').map(row=>row.name),['Action','Arcade']);
  assert.deepEqual(filterSources(rows,'','GB','reverse').map(row=>row.name),['Zelda demos','Arcade']);
  assert.equal(rows[0].name,'Zelda demos');
});

test('extension discovery distinguishes incompatible formats, API versions, installs and updates', () => {
  const row = {format:'retra.catalog',apiMin:1,apiMax:1,versionCode:2};
  assert.equal(extensionStatus(row,null),'Available');
  assert.equal(extensionStatus(row,{metadata:{versionCode:1}}),'Update available');
  assert.equal(extensionStatus(row,{metadata:{versionCode:2}}),'Installed');
  assert.match(extensionStatus({...row,format:'apk'},null),/Incompatible/);
  assert.match(extensionStatus({...row,apiMin:2},null),/Incompatible/);
});

test('Sources list is Mihon-style: last used, Pinned, then platform sections; disabled sources hidden', () => {
  const rows = [
    {key:'a/gba',name:'Advance',platforms:['GBA'],pinned:false},
    {key:'a/multi',name:'Mixed',platforms:['GB','GBC'],pinned:false},
    {key:'a/pin',name:'Pinned one',platforms:['GB'],pinned:true},
    {key:'a/off',name:'Off',platforms:['GB'],pinned:false,enabled:false}
  ];
  const sections = sourceSections(rows,{lastUsed:'a/gba'});
  assert.deepEqual(sections.map(s => s.title),['','Pinned','GBA','Multi']);
  assert.equal(sections[0].rows[0].key,'a/gba');
  assert.ok(!sections.flatMap(s => s.rows).some(row => row.key === 'a/off'));
  // Searching drops the last-used shortcut and keeps only matches.
  assert.deepEqual(sourceSections(rows,{query:'mix',lastUsed:'a/gba'}).map(s => s.title),['Multi']);
});

test('Extensions list groups updates, installed, then available by repository; badge counts updates', () => {
  const meta = (id,versionCode,extra={}) => ({id,name:id,publisher:'P',versionCode,format:'retra.catalog',apiMin:1,apiMax:1,platforms:['GB'],...extra});
  const repo = {url:'https://r.example/index.json',name:'Repo',trusted:true,index:{extensions:[meta('upd',2),meta('cur',1),meta('new',1),meta('old',1,{apiMin:2,apiMax:2})]}};
  const snapshot = {repositories:[repo],installed:[
    {repository:repo.url,metadata:meta('upd',1)},
    {repository:repo.url,metadata:meta('cur',1)},
    {repository:'https://gone.example/index.json',metadata:meta('orphan',1)}
  ]};
  const rows = extensionRows(snapshot);
  assert.equal(pendingUpdates(rows),1);
  const sections = extensionSections(rows);
  assert.deepEqual(sections.map(s => s.title),['Updates pending','Installed','Repo']);
  assert.deepEqual(sections[1].rows.map(r => r.meta.id).sort(),['cur','orphan']);
  assert.match(rows.find(r => r.meta.id === 'old').status,/Incompatible/);
  assert.deepEqual(extensionSections(rows,'new').map(s => s.rows.map(r => r.meta.id)),[['new']]);
});

test('Browse UI reuses Retra shell components: Material icons, two tabs, no glyph icons or header dividers', () => {
  const page = html.match(/<section class="page browse-surface" id="browsePage">[\s\S]*?<\/section>\s*<!-- EXTENSION REPOS -->/)[0];
  assert.deepEqual([...page.matchAll(/class="browse-tab" id="(\w+)"/g)].map(m => m[1]),['sourcesTab','extensionsTab']);
  assert.match(page,/class="search-panel browse-search"/);
  const header = html.match(/<header class="main-header" id="browseHeader">[\s\S]*?<\/header>/)[0];
  assert.match(header,/class="library-overflow-menu"/);
  assert.equal((header.match(/<svg class="ms"/g) || []).length,5);
  const catalog = html.match(/<section class="page settings-detail-page browse-surface" id="catalogPage">[\s\S]*?<\/section>/)[0];
  assert.doesNotMatch(catalog,/[⌕☷⋮]/);
  assert.match(catalog,/class="library-grid library-display-compact browse-catalog-grid"/);
  const css = fs.readFileSync(path.join(root,'app/src/main/assets/retra/style-browse.css'),'utf8');
  assert.doesNotMatch(css.match(/\.browse-tabs\{[^}]*\}/)[0],/border-bottom/);
  const shell = fs.readFileSync(path.join(root,'app/src/main/assets/retra/style-material-shell.css'),'utf8');
  assert.match(shell,/:is\(#libraryPage,#catalogPage\) \.library-grid\.library-display-compact \.cover-card\{/);
});

test('only redistributable licenses enable the Download action, in sync with Kotlin and the repo builder', () => {
  for (const license of ['CC0-1.0','MIT','GPL-3.0-or-later','Zlib','CC-BY-NC-SA','CC-BY-SA-4.0','GPL-3.0-or-later AND CC-BY-NC-SA-4.0','homebrew-freeware'])
    assert.equal(isAuthorizedDownload({authorized:true,license}),true,license);
  for (const license of ['proprietary','CC-BY ish','','MIT AND proprietary'])
    assert.equal(isAuthorizedDownload({authorized:true,license}),false,license);
  const js = fs.readFileSync(path.join(root,'app/src/main/assets/retra/browse-ui.js'),'utf8').match(/const licensePattern = \/\^(.*)\$\/;/)[1];
  const builder = fs.readFileSync(path.join(root,'tools/extension-repo/build-repo.cjs'),'utf8').match(/const LICENSE = \/\^(.*)\$\/;/)[1];
  const kotlin = fs.readFileSync(path.join(root,'app/src/main/java/com/retra/emulator/BrowseModels.kt'),'utf8')
    .match(/val LICENSE = Regex\("([^"]*)" \+\s*"([^"]*)"\)/).slice(1).join('').replaceAll('\\\\','\\');
  assert.equal(builder,js); assert.equal(kotlin,js);
  assert.equal(isAuthorizedDownload({authorized:true,license:'proprietary'}),false);
  assert.equal(isAuthorizedDownload({authorized:false,license:'CC0-1.0'}),false);
  assert.equal(isAuthorizedDownload(undefined),false);
});

test('repository builder maps Homebrew Hub entries and only authorizes declared redistributable licenses', () => {
  const {toGame, normalizeLicense, acceptedUrl} = require('../tools/extension-repo/build-repo.cjs');
  for (const bad of ['http://a.example/','https://a.example/#top','https://a.example/x y','https://u:p@a.example/','https://a.example:8443/','https://localhost/'])
    assert.equal(acceptedUrl(bad),false,bad);
  assert.equal(acceptedUrl('https://a.example/game'),true);
  assert.equal(normalizeLicense({license:'MIT / CC-BY-4.0 (Assets)'}),'MIT AND CC-BY-4.0');
  assert.equal(normalizeLicense({license:'CC-BY-SA 4.0'}),'CC-BY-SA-4.0');
  assert.equal(normalizeLicense({gameLicense:'GPL-3.0-or-later',assetsLicense:'CC-BY-NC-SA-4.0'}),'GPL-3.0-or-later AND CC-BY-NC-SA-4.0');
  assert.equal(normalizeLicense({license:'CC-BY ish'}),null);
  assert.equal(normalizeLicense({}),null);
  const entry = {slug:'demo game',title:'Demo',platform:'GB',basepath:'database-gb',developer:[{name:'A'},{name:'B'}],date:'2024-05-01',
    tags:['puzzle'],files:[{filename:'files/demo.GBC',playable:true,default:true},{filename:'manual.pdf',playable:false}],screenshots:['shot.png','cover.png']};
  const {game, license} = toGame(entry);
  assert.equal(license,null);
  assert.equal(game.id,'demo-game'); assert.equal(game.platform,'GBC'); assert.equal(game.developer,'A, B'); assert.equal(game.year,'2024');
  assert.equal(game.cover,'https://hh3.gbdev.io/static/database-gb/entries/demo%20game/cover.png');
  assert.equal(game.website,'https://hh.gbdev.io/game/demo%20game/');
  assert.equal(game.download,undefined);
  assert.equal(toGame({...entry,files:[{filename:'game.nes',playable:true}]}),null);
});

test('Romhack Plaza index is catalog-only: GB/GBC/GBA hacks and translations from sitemap addresses, no downloads', () => {
  const {plazaGame} = require('../tools/extension-repo/build-repo.cjs');
  const hack = plazaGame('https://romhackplaza.org/romhacks/pokemon-legends-x-v2-7-game-boy-advance','2026-07-01T13:16:18+00:00');
  assert.equal(hack.title,'Pokemon Legends X v2.7'); assert.equal(hack.platform,'GBA'); assert.equal(hack.genre,'Pokémon');
  assert.equal(hack.gameType,'romhack'); assert.equal(hack.updatedAt,Date.parse('2026-07-01T13:16:18Z'));
  assert.equal(hack.website,'https://romhackplaza.org/romhacks/pokemon-legends-x-v2-7-game-boy-advance');
  assert.equal(hack.download,undefined); assert.equal(hack.cover,undefined);
  const translation = plazaGame('https://romhackplaza.org/translations/pokemon-crystal-vietnamese-translation-gbc');
  assert.equal(translation.platform,'GBC'); assert.equal(translation.language,'Vietnamese'); assert.equal(translation.gameType,'translation');
  assert.equal(plazaGame('https://romhackplaza.org/romhacks/mole-mania-big-mamas-frame-fix-game-boy-romhack').platform,'GB');
  assert.equal(plazaGame('https://romhackplaza.org/romhacks/breath-of-fire-ii-gba-portraits-by-goodlucktrying-snes'),null);
  assert.equal(plazaGame('https://romhackplaza.org/utilities/some-tool-gba'),null);
  assert.equal(plazaGame('https://romhackplaza.org/romhacks/%F0%9F%97%9D%EF%B8%8F-pokemon-quest-game-boy-advance').title,'Pokemon Quest');
});

test('hack covers use the original game box art only on a confident title match', () => {
  const {matchBoxart, titleTokens} = require('../tools/extension-repo/build-repo.cjs');
  const entry = (name, rank = 0) => {
    const base = name.replace(/\s*\(.*$/,''); const segments = base.split(' - ');
    return {name, rank, variants:[base, ...(segments.length >= 3 ? [segments.slice(0,2).join(' - ')] : [])].map(t => [...new Set(titleTokens(t))])};
  };
  const index = {GB:[entry('Pokemon - Red Version (USA, Europe)'), entry('Tetris (World)'), entry('Pokemon - Yellow Version - Special Pikachu Edition (USA)')],
    GBC:[entry('Pokemon - Crystal Version (USA)')], GBA:[entry('Pokemon (USA)'), entry('Pokemon - FireRed Version (USA)')]};
  assert.match(matchBoxart('Pokemon Fire Red Reignited','GBA',index).name,/FireRed/);
  assert.equal(matchBoxart('Pokemon Legends X','GBA',index),null);          // franchise word alone is not enough
  assert.match(matchBoxart('Pokemon Crystal Pikachu Edition','GBC',index).name,/Crystal/);
  assert.match(matchBoxart('Pokemon Ruthless Red','GBC',index).url,/Nintendo_-_Game_Boy\/master/); // GBC hack of a GB game
  assert.match(matchBoxart('Pokemon Yellow Full Color','GB',index).name,/Yellow/);
  assert.match(matchBoxart('Tetris 1984','GB',index).url,/Named_Boxarts\/Tetris%20\(World\)\.png$/);
  assert.equal(matchBoxart('Completely Original Quest','GB',index),null);
});

test('sample homebrew package and ROM hashes match the published index and catalog', () => {
  const dir = path.join(root,'app/src/androidTest/assets/browse');
  const read = name => fs.readFileSync(path.join(dir,name));
  const digest = bytes => require('node:crypto').createHash('sha256').update(bytes).digest('hex');
  const index = JSON.parse(read('index.json')); const catalog = JSON.parse(read('catalog.json'));
  assert.equal(index.extensions[0].sha256,digest(read('extension.json')));
  const game = catalog.games[0];
  assert.equal(game.download.sha256,digest(read('retra-pad.gb')));
  assert.equal(game.download.size,32768); assert.equal(game.download.license,'CC0-1.0');
});
