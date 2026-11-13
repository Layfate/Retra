(function(root){
  'use strict';
  const platformNames = ['GBA', 'GBC', 'GB'];
  // Licenses that permit redistribution. Keep in sync with BrowseProtocol.LICENSE (BrowseModels.kt).
  const licensePattern = /^(CC0-1\.0|CC-BY(-NC)?(-SA|-ND)?(-[1-4]\.0)?|MIT|0BSD|ISC|BSD-[23]-Clause|Apache-2\.0|(L|A)?GPL-[23]\.0(-only|-or-later)?|MPL-2\.0|Zlib|Unlicense|WTFPL|FSFAP|public-domain|homebrew-freeware)$/;
  const allowedLicense = value => typeof value === 'string' && value.length <= 200 && value.split(' AND ').every(part => licensePattern.test(part));

  // ---------- Pure helpers (unit tested in tests/browse-system.test.cjs) ----------
  const searchText = value => value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
  function filterSources(sources, query, platform, sort){
    return sources.filter(source => searchText(source.name).includes(searchText(query)) && (!platform || source.platforms.includes(platform)))
      .sort((a,b) => sort === 'platform' ? a.platforms.join().localeCompare(b.platforms.join()) || a.name.localeCompare(b.name) :
        (sort === 'reverse' ? -1 : 1) * a.name.localeCompare(b.name));
  }
  function extensionStatus(metadata, installed){
    if (metadata.format !== 'retra.catalog' || metadata.apiMin > 1 || metadata.apiMax < 1 ||
        !(metadata.platforms || []).every(platform => platformNames.includes(platform))) return 'Incompatible with Retra API 1';
    if (!installed) return 'Available';
    return metadata.versionCode > installed.metadata.versionCode ? 'Update available' : 'Installed';
  }
  function platformGroup(source){ return source.platforms.length === 1 ? source.platforms[0] : 'Multi'; }
  /** Mihon-style Sources list: last used, Pinned, then one section per platform. */
  function sourceSections(sources, {query = '', platform = '', sort = 'name', lastUsed = ''} = {}){
    const visible = filterSources(sources.filter(source => source.enabled !== false), query, platform, sort);
    const sections = [];
    const last = !query && visible.find(source => source.key === lastUsed);
    if (last) sections.push({title:'', rows:[last]});
    const pinned = visible.filter(source => source.pinned);
    if (pinned.length) sections.push({title:'Pinned', rows:pinned});
    for (const group of [...platformNames, 'Multi']) {
      const rows = visible.filter(source => !source.pinned && platformGroup(source) === group);
      if (rows.length) sections.push({title:group, rows});
    }
    return sections;
  }
  function extensionRows(snapshot){
    const rows = [];
    (snapshot.repositories || []).forEach(repo => (repo.index?.extensions || []).forEach(meta => rows.push({repo, meta})));
    (snapshot.installed || []).forEach(installed => {
      if (!rows.some(row => row.repo.url === installed.repository && row.meta.id === installed.metadata.id)) {
        // The repository was removed or no longer lists this extension; it stays usable until uninstalled.
        rows.push({repo:{url:installed.repository, name:'No longer listed', trusted:false, missing:true}, meta:installed.metadata});
      }
    });
    return rows.map(row => {
      const installed = (snapshot.installed || []).find(item => item.metadata.id === row.meta.id && item.repository === row.repo.url) || null;
      const conflict = !installed && (snapshot.installed || []).some(item => item.metadata.id === row.meta.id);
      return {...row, installed, status: conflict ? 'Installed from another repository' : extensionStatus(row.meta, installed)};
    });
  }
  /** Mihon-style Extensions list: Updates pending, Installed, then available grouped by repository. */
  function extensionSections(rows, query = ''){
    const needle = searchText(query.trim());
    const visible = rows.filter(row => !needle || searchText(row.meta.name).includes(needle) ||
      searchText(String(row.meta.publisher || '')).includes(needle));
    const byName = (a,b) => a.meta.name.localeCompare(b.meta.name);
    const sections = [];
    const updates = visible.filter(row => row.status === 'Update available').sort(byName);
    const installed = visible.filter(row => row.installed && row.status !== 'Update available').sort(byName);
    if (updates.length) sections.push({title:'Updates pending', rows:updates});
    if (installed.length) sections.push({title:'Installed', rows:installed});
    const available = visible.filter(row => !row.installed);
    [...new Set(available.map(row => row.repo.name))].forEach(name => {
      sections.push({title:name, rows:available.filter(row => row.repo.name === name).sort(byName)});
    });
    return sections;
  }
  function pendingUpdates(rows){ return rows.filter(row => row.status === 'Update available').length; }
  function isAuthorizedDownload(download){ return Boolean(download?.authorized && allowedLicense(download.license)); }
  function gameDownloadAction(game){
    return isAuthorizedDownload(game.download) ? 'direct' : /^https:\/\//i.test(game.website || '') ? 'website' : '';
  }

  const helpers = {filterSources, extensionStatus, sourceSections, extensionRows, extensionSections, pendingUpdates, isAuthorizedDownload, gameDownloadAction};
  if (typeof module === 'object' && module.exports) { module.exports = helpers; return; }

  // ---------- Bridge ----------
  const $ = id => document.getElementById(id);
  const pending = new Map();
  let sequence = 0;
  let snapshot = {sources:[], repositories:[], installed:[], downloads:[]};
  let loaded = false;
  let tab = 'sources';
  let source = null;
  let catalog = {page:0, query:'', filters:{}, mode:'popular', hasNext:false, loading:false, failed:false, generation:0};
  let detail = null;
  let extensionDetail = null;
  const installing = new Set();
  const images = new Map();
  const storage = {
    get(key, fallback){ try { const value = localStorage.getItem(key); return value === null ? fallback : value; } catch (_) { return fallback; } },
    set(key, value){ try { localStorage.setItem(key, value); } catch (_) { /* Per-viewer preference only. */ } }
  };
  const sourcePrefs = {platform: storage.get('retraBrowsePlatform', ''), sort: storage.get('retraBrowseSort', 'name')};

  function request(action, args = {}){
    return new Promise((resolve, reject) => {
      if (!root.AndroidBridge?.browseRequest) { reject(new Error('Browse requires the Android application')); return; }
      const id = `browse-${Date.now()}-${++sequence}`;
      const timer = setTimeout(() => { pending.delete(id); reject(new Error('Request timed out. Retry when connected.')); }, 150000);
      pending.set(id, {resolve, reject, timer});
      try { root.AndroidBridge.browseRequest(id, action, JSON.stringify(args)); }
      catch (error) { clearTimeout(timer); pending.delete(id); reject(error); }
    });
  }
  root.retraBrowseReply = json => {
    const result = JSON.parse(json);
    const promise = pending.get(result.id);
    if (!promise) return;
    clearTimeout(promise.timer); pending.delete(result.id);
    if (result.error) promise.reject(new Error(result.error)); else promise.resolve(result.result);
  };
  function message(error){ showToast(error.message || String(error), 'error', 5000); }
  async function run(task, control){
    if (control) control.disabled = true;
    try { return await task(); } catch (error) { message(error); }
    finally { if (control?.isConnected) control.disabled = false; }
  }

  // ---------- Small DOM builders (all remote strings go through textContent) ----------
  const icons = {
    pin:'M16 9V4h1c.55 0 1-.45 1-1s-.45-1-1-1H7c-.55 0-1 .45-1 1s.45 1 1 1h1v5c0 1.66-1.34 3-3 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3z',
    pinOutline:'M14 4v5c0 1.12.37 2.16 1 3H9c.65-.86 1-1.9 1-3V4h4m3-2H7c-.55 0-1 .45-1 1s.45 1 1 1h1v5c0 1.66-1.34 3-3 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3V4h1c.55 0 1-.45 1-1s-.45-1-1-1z',
    settings:'M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.488.488 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z',
    download:'M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z',
    update:'M16 18v2H8v-2h8zM11 7.99V16h2V7.99h3L12 4 8 7.99h3z',
    extension:'M20.5 11H19V7c0-1.1-.9-2-2-2h-4V3.5C13 2.12 11.88 1 10.5 1S8 2.12 8 3.5V5H4c-1.1 0-1.99.9-1.99 2v3.8H3.5c1.49 0 2.7 1.21 2.7 2.7s-1.21 2.7-2.7 2.7H2V20c0 1.1.9 2 2 2h3.8v-1.5c0-1.49 1.21-2.7 2.7-2.7 1.49 0 2.7 1.21 2.7 2.7V22H17c1.1 0 2-.9 2-2v-4h1.5c1.38 0 2.5-1.12 2.5-2.5S21.88 11 20.5 11z',
    repository:'M17.63 5.84C17.27 5.33 16.67 5 16 5L5 5.01C3.9 5.01 3 5.9 3 7v10c0 1.1.9 1.99 2 1.99L16 19c.67 0 1.27-.33 1.63-.84L22 12l-4.37-6.16zM16 17H5V7h11l3.55 5L16 17z',
    trusted:'M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm-2 16l-4-4 1.41-1.41L10 14.17l6.59-6.59L18 9l-8 8z',
    untrusted:'M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm7 10c0 4.52-2.98 8.69-7 9.93-4.02-1.24-7-5.41-7-9.93V6.3l7-3.11 7 3.11V11z',
    remove:'M16 9v10H8V9h8m-1.5-6h-5l-1 1H5v2h14V4h-3.5l-1-1zM18 7H6v12c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7z',
    grid:'M4 11h5V5H4v6zm0 7h5v-6H4v6zm6 0h5v-6h-5v6zm6 0h5v-6h-5v6zm-6-7h5V5h-5v6zm6-6v6h5V5h-5z',
    list:'M3 14h4v-4H3v4zm0 5h4v-4H3v4zM3 9h4V5H3v4zm5 5h13v-4H8v4zm0 5h13v-4H8v4zM8 5v4h13V5H8z'
  };
  // Stroke icons shared with the ROM detail page (meta lines and action tiles).
  const strokeIcons = {
    person:'<path d="M12 12a4 4 0 1 0-4-4 4 4 0 0 0 4 4Z"></path><path d="M4 20a8 8 0 0 1 16 0"></path>',
    lines:'<path d="M4 7h16M4 12h16M4 17h10"></path>',
    clock:'<circle cx="12" cy="12" r="8"></circle><path d="M12 8v4l3 2"></path>',
    system:'<rect height="12" rx="2" width="16" x="4" y="6"></rect>',
    heart:'<path d="m12 20-6-6a4.5 4.5 0 0 1 6-6 4.5 4.5 0 0 1 6 6l-6 6Z"></path>',
    importRom:'<path d="M12 16V4M7 9l5-5 5 5"></path><path d="M5 20h14"></path>',
    download:'<path d="M12 4v12M7 11l5 5 5-5"></path><path d="M5 20h14"></path>',
    play:'<path d="M8 6v12l10-6z"></path>',
    remove:'<path d="M5 7h14M10 11v6M14 11v6"></path><path d="M6 7l1 13h10l1-13M9 7V4h6v3"></path>'
  };
  const msIcon = path => `<svg class="ms" viewBox="0 0 24 24" aria-hidden="true"><path d="${path}"></path></svg>`;
  const strokeIcon = markup => `<svg viewBox="0 0 24 24" aria-hidden="true">${markup}</svg>`;
  function element(tag, className, text){
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }
  function iconButton(label, path, action, className = ''){
    const node = element('button', `icon-btn ${className}`.trim());
    node.type = 'button'; node.setAttribute('aria-label', label); node.innerHTML = msIcon(path);
    node.addEventListener('click', event => { event.stopPropagation(); run(action, node); });
    return node;
  }
  function textButton(label, action, ariaLabel){
    const node = element('button', 'browse-text-btn', label);
    node.type = 'button';
    if (ariaLabel) node.setAttribute('aria-label', ariaLabel);
    node.addEventListener('click', event => { event.stopPropagation(); run(action, node); });
    return node;
  }
  function tile(label, markup, action, active = false){
    const node = element('button', `rom-action${active ? ' active' : ''}`);
    node.type = 'button'; node.innerHTML = strokeIcon(markup); node.append(element('span', '', label));
    node.addEventListener('click', () => run(action, node));
    return node;
  }
  function spinner(label){
    const node = element('span', 'browse-spinner');
    node.setAttribute('role', 'progressbar'); node.setAttribute('aria-label', label);
    return node;
  }
  function emptyState(container, face, text, actionLabel, action){
    const box = element('div', 'empty-page browse-empty');
    if (face) box.append(element('div', 'kaomoji', face));
    box.append(element('p', '', text));
    if (actionLabel) {
      const next = element('button', 'modal-btn secondary', actionLabel);
      next.type = 'button'; next.addEventListener('click', () => run(action, next)); box.append(next);
    }
    container.append(box);
  }
  function sectionTitle(text){ return element('h3', 'browse-section-title', text); }
  function detailRow(label, value){
    const row = element('div', 'detail-row'); const copy = element('div');
    copy.append(element('strong', '', label), element('span', '', value || 'Unknown'));
    row.append(copy); return row;
  }

  // ---------- Artwork: native fetch, decode and cache; WebView only shows local files ----------
  function imageUrl(url){
    if (!images.has(url)) images.set(url, request('image', {url}).then(row => row.url).catch(error => { images.delete(url); throw error; }));
    return images.get(url);
  }
  const imageObserver = typeof IntersectionObserver === 'function' ? new IntersectionObserver(rows => {
    rows.forEach(row => { if (row.isIntersecting) { imageObserver.unobserve(row.target); resolveImage(row.target); } });
  }, {rootMargin:'300px'}) : null;
  async function resolveImage(holder){
    try {
      const url = await imageUrl(holder.dataset.image);
      if (!holder.isConnected || holder.querySelector(':scope > img')) return;
      const img = element('img'); img.alt = holder.dataset.imageAlt || ''; img.decoding = 'async';
      img.addEventListener('load', () => {
        if (!holder.isConnected) return;
        const placeholder = holder.querySelector(':scope > .cover-art-placeholder, :scope > svg');
        if (placeholder) placeholder.replaceWith(img); else holder.prepend(img);
        holder.classList.remove('no-cover');
      }, {once:true});
      img.addEventListener('error', () => images.delete(holder.dataset.image), {once:true});
      img.src = url;
    } catch (_) { /* Keep the fallback artwork when offline or when the image is invalid. */ }
  }
  function loadCover(holder, url, title){
    if (!url || !holder) return;
    holder.dataset.image = url; holder.dataset.imageAlt = title ? `${title} cover` : '';
    if (imageObserver) imageObserver.observe(holder); else resolveImage(holder);
  }
  function sourceIcon(url, title, large = false){
    const holder = element('span', `browse-icon${large ? ' browse-icon-large' : ''}`);
    holder.setAttribute('aria-hidden', 'true'); holder.innerHTML = msIcon(icons.extension);
    loadCover(holder, url, title); return holder;
  }
  function fallbackArt(card, id, title, platform){
    if (typeof applyFallbackArtwork === 'function') applyFallbackArtwork(card, {id, title, system:platform});
  }

  // ---------- Shared list row: icon, title, subtitle, trailing actions ----------
  function listRow({iconUrl, title, subtitle, onOpen, openClass = 'browse-open', trailing = [], icon}){
    const item = element('div', 'browse-item');
    const main = element(onOpen ? 'button' : 'div', `browse-item-main${onOpen ? ` ${openClass}` : ''}`);
    if (onOpen) { main.type = 'button'; main.addEventListener('click', () => run(onOpen)); }
    if (icon) { const holder = element('span', 'browse-icon'); holder.setAttribute('aria-hidden', 'true'); holder.innerHTML = msIcon(icon); main.append(holder); }
    else main.append(sourceIcon(iconUrl, title));
    const copy = element('span', 'browse-item-copy');
    copy.append(element('strong', '', title));
    const line = element('small');
    subtitle.filter(part => part?.text).forEach((part, index) => {
      if (index) line.append(' • ');
      line.append(element('span', part.tone ? `browse-tone-${part.tone}` : '', part.text));
    });
    copy.append(line); main.append(copy);
    item.append(main, ...trailing);
    return item;
  }

  // ---------- Browse tab: state ----------
  async function state(action = 'state', args){
    if (!loaded) $('browseStatus').textContent = 'Loading…';
    try {
      snapshot = await request(action, args);
      loaded = true;
      renderAll();
      $('browseStatus').textContent = snapshot.errors?.length ? snapshot.errors.join('\n') : '';
    } catch (error) {
      if (!loaded) $('browseStatus').textContent = error.message;
      throw error;
    }
  }
  function renderAll(){ renderSources(); renderExtensions(); renderRepositories(); renderExtensionDetails(); if (tab === 'search') renderGlobal(); }
  function searchQuery(){ return tab !== 'search' && $('browseSearchPanel').classList.contains('open') ? $('browseSearchInput').value.trim() : ''; }

  // ---------- Global search (every enabled source, like Mihon) ----------
  let globalQuery = '';
  let globalRan = '';
  let globalGeneration = 0;
  let globalTimer = 0;
  function renderGlobal(force = false){
    const container = $('browseGlobal');
    if (!loaded) return;
    const query = globalQuery.trim();
    const sources = snapshot.sources.filter(row => row.enabled !== false)
      .sort((a, b) => Number(Boolean(b.pinned)) - Number(Boolean(a.pinned)) || a.name.localeCompare(b.name));
    if (!sources.length) {
      globalRan = ''; container.replaceChildren();
      emptyState(container, '(・o・;)', 'No sources installed. Install a Retra catalog extension to search games.', 'Browse extensions', () => selectTab('extensions'));
      return;
    }
    if (query.length < 2) { globalRan = ''; container.replaceChildren(); emptyState(container, '', 'Type at least two letters to search every installed source.'); return; }
    if (!force && query === globalRan) return;
    globalRan = query;
    const generation = ++globalGeneration;
    container.replaceChildren();
    const sections = sources.map(row => {
      const section = element('section', 'browse-search-section');
      const head = element('button', 'browse-search-head');
      head.type = 'button'; head.setAttribute('aria-label', `Open ${row.name} results for ${query}`);
      const copy = element('span', 'browse-item-copy');
      copy.append(element('strong', '', row.name), element('small', '', row.platforms.join(' · ')));
      head.append(sourceIcon(row.icon, row.name), copy);
      head.insertAdjacentHTML('beforeend', '<svg class="chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6"></path></svg>');
      head.addEventListener('click', () => run(() => openSource(row, 'popular', query)));
      const body = element('div', 'browse-search-body');
      body.append(spinner(`Searching ${row.name}`));
      section.append(head, body); container.append(section);
      return {row, body};
    });
    let next = 0;
    // Three sources at a time; each one fails or succeeds on its own.
    const worker = async () => {
      while (next < sections.length && generation === globalGeneration) {
        const {row, body} = sections[next++];
        try {
          const result = await request('catalog', {source:row.key, page:1, query, filters:{}, mode:'popular'});
          if (generation !== globalGeneration) return;
          body.replaceChildren();
          if (!result.games.length) { body.append(element('p', 'browse-note', 'No results')); continue; }
          const strip = element('div', 'library-grid library-display-compact browse-strip');
          result.games.slice(0, 12).forEach(game => strip.append(gameCard(game, row.key, 'browsePage')));
          body.append(strip);
          if (result.hasNext || result.games.length > 12) body.append(element('p', 'browse-note', 'More results: open the source above.'));
        } catch (error) {
          if (generation !== globalGeneration) return;
          body.replaceChildren(element('p', 'browse-note browse-tone-error', `Unavailable: ${error.message}`));
        }
      }
    };
    return Promise.all([worker(), worker(), worker()]);
  }
  function selectTab(next){
    const previous = tab;
    tab = next;
    for (const [id, name] of [['sourcesTab', 'sources'], ['extensionsTab', 'extensions'], ['searchTab', 'search']]) $(id).setAttribute('aria-selected', String(tab === name));
    $('browseSources').hidden = tab !== 'sources'; $('browseExtensions').hidden = tab !== 'extensions'; $('browseGlobal').hidden = tab !== 'search';
    $('browseFilterBtn').hidden = tab !== 'sources'; $('browseMoreBtn').hidden = tab !== 'extensions'; $('browseSearchBtn').hidden = tab === 'search';
    $('browseOverflowMenu').classList.remove('open');
    const label = {sources:'Search sources', extensions:'Search extensions', search:'Search all sources'}[tab];
    $('browseSearchBtn').setAttribute('aria-label', label);
    $('browseSearchInput').setAttribute('aria-label', label);
    $('browseSearchInput').placeholder = `${label}...`;
    if (tab === 'search') {
      // Global search keeps its own query; the field is always shown on this tab.
      $('browseSearchInput').value = globalQuery;
      $('browseSearchPanel').classList.add('open');
      renderGlobal();
      requestAnimationFrame(() => $('browseSearchInput').focus({preventScroll:true}));
    } else if (previous === 'search') {
      $('browseSearchInput').value = ''; $('browseSearchPanel').classList.remove('open');
    }
    renderSources(); renderExtensions();
  }
  function lastUsedSource(){ return storage.get('retraBrowseLastSource', ''); }

  // ---------- Sources ----------
  function renderSources(){
    const container = $('browseSources'); container.replaceChildren();
    if (!loaded) return;
    if (!snapshot.sources.length) {
      emptyState(container, '(・o・;)', 'No sources installed. Install a Retra catalog extension to browse games.', 'Browse extensions', () => selectTab('extensions'));
      return;
    }
    const sections = sourceSections(snapshot.sources, {query:searchQuery(), platform:sourcePrefs.platform, sort:sourcePrefs.sort, lastUsed:lastUsedSource()});
    if (!sections.length) {
      const allDisabled = snapshot.sources.every(row => row.enabled === false);
      emptyState(container, '', allDisabled ? 'Every source is turned off. Turn sources on from the extension settings.' : 'No sources match your search or filter.');
      return;
    }
    sections.forEach(section => {
      if (section.title) container.append(sectionTitle(section.title));
      section.rows.forEach(row => container.append(sourceRow(row)));
    });
  }
  function sourceRow(row){
    const trailing = [];
    if (row.latest) trailing.push(textButton('Latest', () => openSource(row, 'latest'), `Latest games in ${row.name}`));
    const pin = iconButton(`${row.pinned ? 'Unpin' : 'Pin'} ${row.name}`, row.pinned ? icons.pin : icons.pinOutline,
      () => state('pin', {source:row.key, pinned:!row.pinned}), row.pinned ? 'browse-pinned' : '');
    pin.setAttribute('aria-pressed', String(Boolean(row.pinned)));
    trailing.push(pin);
    return listRow({iconUrl:row.icon, title:row.name, subtitle:[{text:row.platforms.join(' · ')}], onOpen:() => openSource(row), trailing});
  }

  // ---------- Extensions ----------
  const entryKey = entry => `${entry.repo.url}|${entry.meta.id}`;
  function renderExtensions(){
    const container = $('browseExtensions'); container.replaceChildren();
    const rows = extensionRows(snapshot);
    const updates = pendingUpdates(rows);
    $('extensionsBadge').hidden = !updates; $('extensionsBadge').textContent = updates ? String(updates) : '';
    $('extensionsTab').setAttribute('aria-label', updates ? `Extensions, ${updates} update${updates === 1 ? '' : 's'} pending` : 'Extensions');
    if (!loaded) return;
    if (!rows.length) {
      emptyState(container, '(・_・ヾ', snapshot.repositories.length ? 'Your repositories do not list any extensions yet.' :
        'No extension repos yet. Add a Retra repository to discover catalog extensions.', 'Extension repos', openRepositories);
      return;
    }
    const sections = extensionSections(rows, searchQuery());
    if (!sections.length) { emptyState(container, '', 'No extensions match your search.'); return; }
    sections.forEach(section => {
      container.append(sectionTitle(section.title));
      section.rows.forEach(entry => container.append(extensionRow(entry)));
    });
  }
  function extensionSubtitle(entry){
    const {meta, repo, installed, status} = entry;
    const parts = [{text:meta.platforms.join(' · ')}, {text:meta.versionName || String(meta.versionCode)}];
    if (status === 'Update available') parts.push({text:'Update available', tone:'accent'});
    else if (status.startsWith('Incompatible')) parts.push({text:'Incompatible', tone:'error'});
    else if (status === 'Installed from another repository') parts.push({text:'Installed from another repo', tone:'error'});
    else if (!installed && !repo.trusted) parts.push({text:'Untrusted', tone:'error'});
    return parts;
  }
  function extensionRow(entry){
    const {meta, installed, status} = entry;
    const trailing = [];
    if (installed) trailing.push(iconButton(`${meta.name} settings`, icons.settings, () => openExtension(entry)));
    if (installing.has(entryKey(entry))) trailing.push(spinner(`Installing ${meta.name}`));
    else if (status === 'Available' || status === 'Update available') {
      const update = status === 'Update available';
      trailing.push(iconButton(`${update ? 'Update' : 'Install'} ${meta.name}`, update ? icons.update : icons.download, () => confirmInstall(entry)));
    }
    return listRow({iconUrl:meta.icon, title:meta.name, subtitle:extensionSubtitle(entry), onOpen:() => openExtension(entry), openClass:'browse-extension-open', trailing});
  }
  function permissionsText(meta, manifest){
    const hosts = [...new Set((manifest?.sources || []).map(row => { try { return new URL(row.catalogUrl).host; } catch (_) { return ''; } }).filter(Boolean))];
    return [`Publisher: ${meta.publisher} (self-declared)`, `Version: ${meta.versionName || meta.versionCode}`, `Retra API: ${meta.apiMin}–${meta.apiMax}`,
      hosts.length ? `Catalog hosts: ${hosts.join(', ')}` : `Package: ${meta.packageUrl}`,
      'Contents: catalog data and artwork links only. No executable code. ROM downloads always ask first.'].join('\n');
  }
  function confirmInstall(entry){
    const {meta, repo, installed} = entry;
    const verb = installed ? 'Update' : 'Install';
    const trust = repo.trusted ? '' : `\n\n${repo.name} is not trusted yet. Continuing trusts this repository:\n${repo.url}`;
    openConfirmModal(`${verb} ${meta.name}?`, `${permissionsText(meta, installed?.manifest)}${trust}`, () => run(async () => {
      const key = entryKey(entry);
      installing.add(key); renderExtensions();
      try {
        if (!repo.trusted) await request('trustRepository', {url:repo.url, trusted:true});
        snapshot = await request('install', {url:repo.url, id:meta.id});
        showToast(`${meta.name} ${installed ? 'updated' : 'installed'}`);
      } finally {
        installing.delete(key);
        // Refresh after failures too, so a newly trusted repository is reflected.
        try { snapshot = await request('state'); } catch (_) { /* Keep the last snapshot. */ }
        renderAll();
      }
    }));
  }
  function confirmUninstall(entry){
    openConfirmModal(`Uninstall ${entry.meta.name}?`, 'Its sources will be removed. Games you added to your Library, ROM files and save data are kept.',
      () => run(async () => { await state('uninstall', {id:entry.meta.id}); showToast(`${entry.meta.name} uninstalled`); }));
  }
  function openExtension(entry){
    extensionDetail = {repo:entry.repo.url, id:entry.meta.id};
    renderExtensionDetails();
    openSubPage('extensionDetailsPage');
  }
  function renderExtensionDetails(){
    const container = $('extensionDetails');
    if (!container || !extensionDetail) return;
    container.replaceChildren();
    const entry = extensionRows(snapshot).find(row => row.repo.url === extensionDetail.repo && row.meta.id === extensionDetail.id);
    if (!entry) { emptyState(container, '', 'This extension is no longer listed by your repositories.'); return; }
    const {meta, repo, installed, status} = entry;
    const hero = element('div', 'browse-extension-hero');
    const copy = element('div', 'browse-item-copy');
    copy.append(element('strong', '', meta.name), element('small', '', `${meta.publisher} • ${meta.versionName || meta.versionCode}`));
    hero.append(sourceIcon(meta.icon, meta.name, true), copy);
    container.append(hero);
    const actions = element('div', 'rom-actions browse-actions');
    if (installing.has(entryKey(entry))) actions.append(spinner(`Installing ${meta.name}`));
    else if (status === 'Available' || status === 'Update available') {
      actions.append(tile(status === 'Available' ? 'Install' : 'Update', strokeIcons.download, () => confirmInstall(entry)));
    }
    if (installed) actions.append(tile('Uninstall', strokeIcons.remove, () => confirmUninstall(entry)));
    if (actions.children.length) container.append(actions);

    if (installed) {
      container.append(sectionTitle('Sources'));
      const list = element('div', 'detail-list');
      (installed.manifest?.sources || []).forEach(definition => {
        const key = `${meta.id}/${definition.id}`;
        const current = snapshot.sources.find(row => row.key === key);
        const row = element('div', 'detail-row'); const text = element('div');
        text.append(element('strong', '', definition.name), element('span', '', (definition.platforms || []).join(' · ')));
        const toggle = element('label', 'switch'); const input = element('input'); input.type = 'checkbox';
        input.checked = current?.enabled !== false; input.setAttribute('aria-label', `Show ${definition.name} in Sources`);
        input.addEventListener('change', () => run(() => state('enableSource', {source:key, enabled:input.checked})).then(() => renderExtensionDetails()));
        toggle.append(input, element('span', 'slider')); row.append(text, toggle); list.append(row);
      });
      container.append(list);
    }
    container.append(sectionTitle('Information'));
    const info = element('div', 'detail-list');
    [['Status', status], ['Platforms', meta.platforms.join(' · ')], ['Retra API', `${meta.apiMin}–${meta.apiMax}`],
      ['Repository', `${repo.name} • ${repo.trusted ? 'Trusted' : 'Untrusted'}`], ['Repository URL', repo.url],
      ['Package SHA-256', meta.sha256], ['Contents', 'Catalog data only. No executable code.']].forEach(([label, value]) => info.append(detailRow(label, value)));
    container.append(info);
  }

  // ---------- Repositories ----------
  function openRepositories(){ $('browseOverflowMenu').classList.remove('open'); renderRepositories(); openSubPage('repositoriesPage'); }
  function renderRepositories(){
    const container = $('repositoryList'); container.replaceChildren();
    if (!snapshot.repositories.length) { emptyState(container, '(・_・ヾ', 'No repositories yet. Tap Add and paste a repository index URL.'); return; }
    snapshot.repositories.forEach(repo => {
      const status = repo.error ? {text:repo.error, tone:'error'} : {text:repo.trusted ? 'Trusted' : 'Untrusted', tone:repo.trusted ? '' : 'error'};
      const trust = iconButton(repo.trusted ? `Stop trusting ${repo.name}` : `Trust ${repo.name}`, repo.trusted ? icons.trusted : icons.untrusted, () => {
        if (repo.trusted) return state('trustRepository', {url:repo.url, trusted:false});
        openConfirmModal('Trust repository?', `${repo.name}\n${repo.url}\n\nThis repository controls which catalog extensions and download details you see. Its publisher name is self-declared, so confirm the address with the publisher first.`,
          () => run(() => state('trustRepository', {url:repo.url, trusted:true})));
      }, repo.trusted ? 'browse-pinned' : '');
      trust.setAttribute('aria-pressed', String(Boolean(repo.trusted)));
      const remove = iconButton(`Remove ${repo.name}`, icons.remove, () => openConfirmModal('Remove repository?',
        'Installed extensions, Library games, ROM files and saves are kept. New installs and downloads from this repository stop.',
        () => run(() => state('removeRepository', {url:repo.url}))));
      container.append(listRow({icon:icons.repository, title:repo.name, subtitle:[{text:repo.url}, status], trailing:[trust, remove]}));
    });
  }
  function openRepositoryModal(){ $('repositoryUrl').value = ''; $('repositoryModal').classList.add('open'); requestAnimationFrame(() => $('repositoryUrl').focus()); }
  function closeRepositoryModal(){ $('repositoryModal').classList.remove('open'); }

  // ---------- Source catalog ----------
  function rememberSource(key){ storage.set('retraBrowseLastSource', key); }
  function syncCatalogColumns(){
    const grid = $('catalogGames');
    const columns = $('libraryGrid')?.dataset.itemsPerRow || '3';
    grid.style.setProperty('--library-columns', columns); grid.dataset.itemsPerRow = columns;
  }
  function setCatalogView(list){
    const grid = $('catalogGames');
    grid.classList.toggle('library-display-list', list); grid.classList.toggle('library-display-compact', !list);
    $('catalogView').setAttribute('aria-label', list ? 'Switch to grid view' : 'Switch to list view');
    $('catalogView').setAttribute('aria-pressed', String(list));
    $('catalogView').innerHTML = msIcon(list ? icons.grid : icons.list);
    storage.set('retraBrowseList', String(list));
  }
  let suppressFilterReset = false;
  function openSource(row, mode = 'popular', query = ''){
    source = row; rememberSource(row.key);
    $('catalogTitle').textContent = row.name;
    $('catalogLatest').hidden = !row.latest;
    $('catalogWebsite').hidden = !row.website;
    $('catalogQuery').value = query; $('catalogSearchForm').classList.toggle('open', Boolean(query));
    suppressFilterReset = true; $('catalogFilters').reset(); suppressFilterReset = false;
    syncCatalogColumns();
    openSubPage('catalogPage');
    renderSources();
    return loadCatalog(true, false, mode);
  }
  function catalogFilters(){
    return Object.fromEntries([...new FormData($('catalogFilters'))].map(([key, value]) => [key, String(value).trim()]).filter(([, value]) => value));
  }
  function inLibrary(sourceKey, gameId){
    try { return libraryRoms.some(rom => rom.catalogSource === sourceKey && rom.catalogGameId === gameId); } catch (_) { return false; }
  }
  function gameCard(game, sourceKey = source.key, back = 'catalogPage'){
    const card = element('article', 'cover-card no-cover browse-game');
    card.tabIndex = 0; card.setAttribute('role', 'button');
    card.setAttribute('aria-label', `${game.title}, ${game.platform}`);
    const placeholder = element('div', 'cover-art-placeholder'); placeholder.setAttribute('aria-hidden', 'true');
    const info = element('div', 'cover-info'); info.append(element('strong', '', game.title));
    card.append(placeholder, element('span', 'top-badge', game.platform), info);
    if (inLibrary(sourceKey, game.id)) card.append(element('span', 'browse-in-library', 'In library'));
    fallbackArt(card, `${sourceKey}/${game.id}`, game.title, game.platform);
    loadCover(card, game.cover, game.title);
    const open = () => openDetails(sourceKey, game.id, back);
    card.addEventListener('click', open);
    card.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); open(); } });
    return card;
  }
  function sentinelVisible(){
    const sentinel = $('catalogSentinel'); const scroller = root.appMain || document.getElementById('appMain');
    if (!sentinel?.isConnected || !scroller) return false;
    return sentinel.getBoundingClientRect().top < scroller.getBoundingClientRect().bottom + 600;
  }
  async function loadCatalog(reset = false, refresh = false, mode = catalog.mode){
    if (!source || (catalog.loading && !reset)) return;
    if (!reset && !catalog.hasNext && !catalog.failed) return;
    const generation = reset ? catalog.generation + 1 : catalog.generation;
    if (reset) {
      catalog = {page:0, query:$('catalogQuery').value.trim(), filters:catalogFilters(), mode, hasNext:false, loading:false, failed:false, generation};
      $('catalogGames').replaceChildren();
      const scroller = document.getElementById('appMain'); if (scroller) scroller.scrollTop = 0;
    }
    catalog.loading = true; catalog.failed = false;
    $('catalogMore').hidden = true;
    $('catalogPopular').setAttribute('aria-pressed', String(catalog.mode === 'popular'));
    $('catalogLatest').setAttribute('aria-pressed', String(catalog.mode === 'latest'));
    $('catalogFilter').setAttribute('aria-pressed', String(Object.keys(catalog.filters).length > 0));
    const grid = $('catalogGames');
    if (!grid.children.length) $('catalogStatus').textContent = 'Loading games…';
    $('catalogSentinel').classList.add('loading');
    try {
      const result = await request('catalog', {source:source.key, page:catalog.page + 1, query:catalog.query, filters:catalog.filters, mode:catalog.mode, refresh});
      if (generation !== catalog.generation) return;
      catalog.page = result.page; catalog.hasNext = result.hasNext;
      const fragment = document.createDocumentFragment();
      result.games.forEach(game => fragment.append(gameCard(game)));
      grid.append(fragment);
      const filtered = catalog.query || Object.keys(catalog.filters).length;
      $('catalogStatus').textContent = !grid.children.length ? (filtered ? 'No games match your search or filters.' : 'This source has no games yet.') :
        (result.offline ? 'Offline. Showing the cached catalog.' : '');
    } catch (error) {
      if (generation !== catalog.generation) return;
      catalog.failed = true;
      $('catalogStatus').textContent = grid.children.length ? `Could not load more games. ${error.message}` : error.message;
    } finally {
      if (generation === catalog.generation) {
        catalog.loading = false;
        $('catalogSentinel').classList.remove('loading');
        $('catalogMore').textContent = catalog.failed ? 'Retry' : 'Load more';
        $('catalogMore').hidden = !(catalog.failed || (typeof IntersectionObserver !== 'function' && catalog.hasNext));
        // Keep filling the screen when the first pages are shorter than the viewport.
        if (!catalog.failed && catalog.hasNext && activePageId() === 'catalogPage' && sentinelVisible()) requestAnimationFrame(() => loadCatalog());
      }
    }
  }

  // ---------- Game details ----------
  async function openDetails(sourceId, gameId, back = 'catalogPage'){
    detail = {source:sourceId, gameId, back};
    $('catalogDetailsBack').dataset.backTo = back;
    openSubPage('catalogDetailsPage');
    $('catalogDetails').replaceChildren(element('p', 'browse-status', 'Loading game details…'));
    try {
      const result = await request('details', {source:sourceId, gameId});
      if (detail.source !== sourceId || detail.gameId !== gameId) return;
      Object.assign(detail, result); renderDetails();
    } catch (error) {
      if (detail.source !== sourceId || detail.gameId !== gameId) return;
      const container = $('catalogDetails'); container.replaceChildren();
      emptyState(container, '', error.message, 'Retry', () => openDetails(sourceId, gameId, back));
    }
  }
  function metaLine(markup, text){
    const line = element('div', 'meta-line'); line.innerHTML = strokeIcon(markup); line.append(element('span', '', text)); return line;
  }
  function renderDetails(){
    if (!detail?.game) return;
    const {game, library} = detail;
    const container = $('catalogDetails'); container.replaceChildren();
    const download = game.download;
    const authorized = isAuthorizedDownload(download);
    const installed = Boolean(library?.fileAvailable);
    const sourceName = library?.sourceName || (source?.key === detail.source ? source.name : '') ||
      snapshot.sources.find(row => row.key === detail.source)?.name || detail.source;

    const top = element('div', 'rom-top browse-detail-top');
    const cover = element('div', 'rom-cover browse-detail-cover no-cover');
    const placeholder = element('div', 'cover-art-placeholder'); placeholder.setAttribute('aria-hidden', 'true'); cover.append(placeholder);
    fallbackArt(cover, `${detail.source}/${game.id}`, game.title, game.platform);
    loadCover(cover, game.cover, game.title);
    const meta = element('div', 'rom-meta');
    meta.append(element('h2', '', game.title),
      metaLine(strokeIcons.person, game.developer || 'Unknown developer'),
      metaLine(strokeIcons.lines, [game.publisher, game.year].filter(Boolean).join(' • ') || 'Unknown publisher'),
      metaLine(strokeIcons.clock, `${installed ? 'Installed' : 'Not installed'} • ${sourceName}`),
      metaLine(strokeIcons.system, game.platform));
    top.append(cover, meta); container.append(top);

    const actions = element('div', 'rom-actions browse-actions browse-detail-actions');
    actions.append(tile(library?.inLibrary ? 'In library' : 'Add to library', strokeIcons.heart, async () => {
      if (library?.inLibrary) {
        openConfirmModal('Remove from Library?', 'The ROM file, saves and game data are kept.', () => run(async () => {
          await removeRomFromLibrary(library.romId); await updateDetail();
        }));
      } else { await request('addLibrary', detailArgs()); await updateDetail(); showToast('Added to Library'); }
    }, Boolean(library?.inLibrary)));
    actions.append(tile('Import ROM', strokeIcons.importRom, () => request('import', detailArgs())));
    const action = gameDownloadAction(game);
    const downloadTile = tile(action === 'website' ? 'Download on website' : 'Download', strokeIcons.download, () => action === 'website' ? request('website', detailArgs()) : openConfirmModal('Download this game?',
      `${download.fileName}\nLicense: ${download.license}\nRights: ${download.rightsUrl}\nSize: ${Math.ceil(download.size / 1024)} KiB\n\nThe source states this file may be distributed. Continue only if that is true for you.`,
      () => run(async () => { const result = await request('download', detailArgs()); detail.downloadId = result.id; renderProgress(); })));
    downloadTile.disabled = !action;
    if (!action) downloadTile.setAttribute('aria-label', 'Download unavailable: no file or website');
    actions.append(downloadTile);
    const play = tile('Play', strokeIcons.play, () => launchNativeRom({dataset:{romId:library.romId, title:game.title, fileName:library.fileName, system:game.platform}}));
    play.disabled = !installed; actions.append(play);
    container.append(actions);

    const progress = element('div', 'browse-download'); progress.id = 'browseDownloadProgress'; progress.setAttribute('role', 'status');
    container.append(progress); renderProgress();

    container.append(element('p', 'browse-description', game.description || 'No description provided.'));
    const chips = element('div', 'chip-row');
    [game.genre, game.gameType, game.region, game.language].filter(Boolean).flatMap(value => String(value).split(','))
      .map(value => value.trim()).filter(Boolean).forEach(value => chips.append(element('span', 'genre-chip', value)));
    if (chips.children.length) container.append(chips);

    if (game.screenshots?.length) {
      container.append(sectionTitle('Screenshots'));
      const strip = element('div', 'browse-screenshots');
      game.screenshots.forEach((url, index) => {
        const shot = element('div', 'browse-screenshot'); shot.setAttribute('role', 'img');
        shot.setAttribute('aria-label', `${game.title} screenshot ${index + 1}`);
        loadCover(shot, url, game.title); strip.append(shot);
      });
      container.append(strip);
    }
    container.append(sectionTitle('Information'));
    const info = element('div', 'detail-list');
    [['Source', sourceName], ['Source game ID', game.id], ['Developer', game.developer], ['Publisher', game.publisher],
      ['Release year', game.year], ['Platform', game.platform],
      ['File', authorized ? `Authorized download • ${download.license}` : action === 'website' ? 'Open the website inside Retra and choose a supported ROM, ZIP or patch download.' : 'Catalog only. Get the file from the developer, then use Import ROM.'],
      ['Local ROM', installed ? library.fileName : 'Not installed']].forEach(([label, value]) => info.append(detailRow(label, value)));
    // External pages use an isolated browser with supported downloads back into Library.
    if (/^https:\/\//i.test(game.website || '')) {
      const link = element('a', 'detail-row tappable browse-link');
      link.href = game.website; link.rel = 'noopener noreferrer';
      link.addEventListener('click', event => { event.preventDefault(); run(() => request('website', detailArgs())); });
      const copy = element('div'); copy.append(element('strong', '', 'Website'), element('span', '', game.website));
      link.append(copy); link.insertAdjacentHTML('beforeend', '<svg class="chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6"></path></svg>');
      info.append(link);
    }
    container.append(info);
  }
  function detailArgs(){ return {source:detail.source, gameId:detail.gameId}; }
  async function updateDetail(){
    if (!detail) return;
    const active = detail; const result = await request('details', detailArgs());
    if (detail === active) { Object.assign(detail, result); renderDetails(); }
  }
  function renderProgress(){
    const container = $('browseDownloadProgress'); if (!container || !detail) return;
    container.replaceChildren();
    const row = snapshot.downloads.find(item => item.source === detail.source && item.gameId === detail.gameId);
    if (!row || row.status === 'complete') return;
    const labels = {downloading:'Downloading', importing:'Adding to Library', interrupted:'Paused'};
    container.append(element('p', 'browse-note', `${labels[row.status] || row.status} • ${Math.round((row.bytes || 0) / 1024)} / ${Math.round(row.total / 1024)} KiB${row.error ? ` • ${row.error}` : ''}`));
    if (['downloading', 'importing'].includes(row.status)) {
      const bar = element('progress'); bar.max = row.total; bar.value = row.bytes || 0; bar.setAttribute('aria-label', 'ROM download progress');
      container.append(bar);
      if (row.status === 'downloading') {
        const pause = element('button', 'modal-btn secondary', 'Pause'); pause.type = 'button';
        pause.addEventListener('click', () => run(() => request('cancelDownload', {id:row.id}), pause)); container.append(pause);
      }
    }
  }

  // ---------- Native callbacks ----------
  root.retraBrowseProgress = json => {
    const row = JSON.parse(json);
    snapshot.downloads = snapshot.downloads.filter(item => item.id !== row.id).concat(row);
    renderProgress();
    if (row.status === 'complete') { showToast('Download complete'); run(updateDetail); }
  };
  root.retraBrowseLibraryChanged = () => {
    hydrateLibraryFromNativeRoom(); renderLibraryFromStorage({force:true});
    if (activePageId() === 'catalogDetailsPage') run(updateDetail);
  };
  root.retraBrowseImportError = json => message(new Error(JSON.parse(json).error));

  // ---------- Sheets, menus and Android back ----------
  function openSheet(sheet){ sheet.classList.add('open'); sheet.setAttribute('aria-hidden', 'false'); }
  function closeSheet(sheet){ sheet.classList.remove('open'); sheet.setAttribute('aria-hidden', 'true'); }
  function updateSourceSheet(){
    document.querySelectorAll('#browsePlatformModes [data-platform]').forEach(button => {
      const selected = button.dataset.platform === sourcePrefs.platform;
      button.classList.toggle('selected', selected); button.setAttribute('aria-pressed', String(selected));
    });
    document.querySelectorAll('#browseSortOptions [data-browse-sort]').forEach(button => {
      const key = button.dataset.browseSort;
      const selected = key === 'name' ? ['name', 'reverse'].includes(sourcePrefs.sort) : sourcePrefs.sort === key;
      button.classList.toggle('selected', selected); button.setAttribute('aria-pressed', String(selected));
      button.querySelector('.library-sort-direction').textContent = key === 'name' && sourcePrefs.sort === 'reverse' ? '↑' : '↓';
    });
    $('browseFilterBtn').classList.toggle('active', Boolean(sourcePrefs.platform));
  }
  function setSourceSheetTab(name){
    document.querySelectorAll('[data-browse-sheet-tab]').forEach(button => {
      const selected = button.dataset.browseSheetTab === name;
      button.classList.toggle('active', selected); button.setAttribute('aria-selected', String(selected));
    });
    document.querySelectorAll('[data-browse-sheet-panel]').forEach(panel => panel.classList.toggle('active', panel.dataset.browseSheetPanel === name));
  }
  const closables = () => [
    ['#browseFilterSheet.open', () => closeSheet($('browseFilterSheet'))],
    ['#catalogFilterSheet.open', () => closeSheet($('catalogFilterSheet'))],
    ['#repositoryModal.open', closeRepositoryModal],
    ['#browseOverflowMenu.open', () => $('browseOverflowMenu').classList.remove('open')],
    ['#catalogOverflowMenu.open', () => $('catalogOverflowMenu').classList.remove('open')]
  ];
  const previousBack = root.retraHandleAndroidBack;
  root.retraHandleAndroidBack = function(){
    for (const [selector, close] of closables()) if (document.querySelector(selector)) { close(); return true; }
    if (activePageId() === 'browsePage' && tab !== 'search' && $('browseSearchPanel').classList.contains('open')) { toggleBrowseSearch(false); return true; }
    return typeof previousBack === 'function' ? previousBack() : false;
  };
  function toggleBrowseSearch(open = !$('browseSearchPanel').classList.contains('open')){
    $('browseSearchPanel').classList.toggle('open', open);
    if (open) requestAnimationFrame(() => $('browseSearchInput').focus({preventScroll:true}));
    else { $('browseSearchInput').value = ''; $('browseSearchInput').blur(); }
    renderSources(); renderExtensions();
  }

  // ---------- Wiring ----------
  $('sourcesTab').addEventListener('click', () => selectTab('sources'));
  $('extensionsTab').addEventListener('click', () => selectTab('extensions'));
  $('browseSearchBtn').addEventListener('click', () => toggleBrowseSearch());
  $('searchTab').addEventListener('click', () => selectTab('search'));
  $('browseSearchInput').addEventListener('input', () => {
    if (tab === 'search') {
      globalQuery = $('browseSearchInput').value;
      clearTimeout(globalTimer); globalTimer = setTimeout(() => renderGlobal(), 450);
      return;
    }
    renderSources(); renderExtensions();
  });
  $('browseSearchInput').addEventListener('keydown', event => {
    if (event.key !== 'Enter' || tab !== 'search') return;
    clearTimeout(globalTimer); globalQuery = event.target.value; renderGlobal(true); event.target.blur();
  });
  $('browseFilterBtn').addEventListener('click', event => { event.stopPropagation(); updateSourceSheet(); setSourceSheetTab('filter'); openSheet($('browseFilterSheet')); });
  $('browseMoreBtn').addEventListener('click', event => { event.stopPropagation(); $('browseOverflowMenu').classList.toggle('open'); });
  $('browseRefresh').addEventListener('click', () => {
    $('browseOverflowMenu').classList.remove('open');
    run(async () => {
      await state('refresh');
      const updates = pendingUpdates(extensionRows(snapshot));
      if (!snapshot.errors?.length) showToast(updates ? `${updates} extension update${updates === 1 ? '' : 's'} available` : 'Extensions are up to date');
    });
  });
  $('browseRepositories').addEventListener('click', openRepositories);
  document.querySelectorAll('[data-browse-sheet-tab]').forEach(button => button.addEventListener('click', () => setSourceSheetTab(button.dataset.browseSheetTab)));
  document.querySelectorAll('#browsePlatformModes [data-platform]').forEach(button => button.addEventListener('click', () => {
    sourcePrefs.platform = button.dataset.platform; storage.set('retraBrowsePlatform', sourcePrefs.platform); updateSourceSheet(); renderSources();
  }));
  document.querySelectorAll('#browseSortOptions [data-browse-sort]').forEach(button => button.addEventListener('click', () => {
    const key = button.dataset.browseSort;
    sourcePrefs.sort = key === 'name' ? (sourcePrefs.sort === 'name' ? 'reverse' : 'name') : key;
    storage.set('retraBrowseSort', sourcePrefs.sort); updateSourceSheet(); renderSources();
  }));
  for (const id of ['browseFilterSheet', 'catalogFilterSheet']) $(id).addEventListener('click', event => { if (event.target === $(id)) closeSheet($(id)); });
  $('addRepositoryBtn').addEventListener('click', openRepositoryModal);
  $('cancelRepositoryBtn').addEventListener('click', closeRepositoryModal);
  $('repositoryModal').addEventListener('click', event => { if (event.target === $('repositoryModal')) closeRepositoryModal(); });
  $('repositoryForm').addEventListener('submit', event => {
    event.preventDefault();
    run(async () => {
      await state('addRepository', {url:$('repositoryUrl').value.trim()});
      closeRepositoryModal(); showToast('Repository added');
    }, $('saveRepositoryBtn'));
  });
  document.querySelector('.nav-item[data-page="browsePage"]').addEventListener('click', () => run(() => state()));
  $('catalogSearchButton').addEventListener('click', () => {
    const form = $('catalogSearchForm');
    const open = !form.classList.contains('open');
    form.classList.toggle('open', open);
    if (open) $('catalogQuery').focus();
    else if ($('catalogQuery').value) { $('catalogQuery').value = ''; run(() => loadCatalog(true)); }
  });
  $('catalogSearchForm').addEventListener('submit', event => { event.preventDefault(); $('catalogQuery').blur(); run(() => loadCatalog(true)); });
  $('catalogView').addEventListener('click', () => setCatalogView(!$('catalogGames').classList.contains('library-display-list')));
  setCatalogView(storage.get('retraBrowseList', 'false') === 'true');
  $('catalogPopular').addEventListener('click', () => run(() => loadCatalog(true, false, 'popular')));
  $('catalogLatest').addEventListener('click', () => run(() => loadCatalog(true, false, 'latest')));
  $('catalogFilter').addEventListener('click', () => openSheet($('catalogFilterSheet')));
  $('catalogFilters').addEventListener('submit', event => { event.preventDefault(); closeSheet($('catalogFilterSheet')); run(() => loadCatalog(true)); });
  $('catalogFilters').addEventListener('reset', () => {
    if (suppressFilterReset) return;
    setTimeout(() => { closeSheet($('catalogFilterSheet')); run(() => loadCatalog(true)); }, 0);
  });
  $('catalogMore').addEventListener('click', () => run(() => loadCatalog(), $('catalogMore')));
  $('catalogMoreBtn').addEventListener('click', event => { event.stopPropagation(); $('catalogOverflowMenu').classList.toggle('open'); });
  $('catalogRefresh').addEventListener('click', () => { $('catalogOverflowMenu').classList.remove('open'); run(() => loadCatalog(true, true)); });
  $('catalogWebsite').addEventListener('click', () => {
    $('catalogOverflowMenu').classList.remove('open');
    run(() => request('openSourceWebsite', {source:source.key, query:$('catalogQuery').value.trim()}));
  });
  $('catalogSourceInfo').addEventListener('click', () => {
    $('catalogOverflowMenu').classList.remove('open');
    if (source) openConfirmModal(source.name, `Source ID: ${source.key}\nPlatforms: ${source.platforms.join(', ')}\nCatalog: ${source.catalogUrl}`, () => {});
  });
  document.addEventListener('click', event => {
    for (const [menu, trigger] of [['browseOverflowMenu', 'browseMoreBtn'], ['catalogOverflowMenu', 'catalogMoreBtn']]) {
      if (!$(menu).contains(event.target) && !$(trigger).contains(event.target)) $(menu).classList.remove('open');
    }
  });
  if (typeof IntersectionObserver === 'function') {
    new IntersectionObserver(rows => {
      if (rows.some(row => row.isIntersecting) && activePageId() === 'catalogPage' && catalog.hasNext && !catalog.loading && !catalog.failed) run(() => loadCatalog());
    }, {root:document.getElementById('appMain'), rootMargin:'0px 0px 600px 0px'}).observe($('catalogSentinel'));
  }

  root.RetraBrowse = {...helpers, openDetails, loadCover};
  selectTab('sources');
  updateSourceSheet();
  if (libraryRoms.some(rom => rom.catalogSource)) renderLibraryFromStorage({force:true});
})(typeof window === 'object' ? window : globalThis);
