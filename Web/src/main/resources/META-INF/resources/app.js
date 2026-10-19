/*
 * SkyVault — logika interfejsu.
 *
 * Mechanika przeniesiona ze starego projektu (dysk, biblioteki, kosz,
 * gwiazdki, menu kontekstowe, przeciąganie), ale cały widok jest czarno-biały.
 */
(function () {
  'use strict';

  const C = window.SkyVaultCrypto;

  // --- Stałe ---

  const MAX_UPLOAD_BYTES = 100 * 1024 * 1024 - 4096;
  const PREVIEW_TEXT_LIMIT = 512 * 1024;

  const VIEW_TITLES = {
    home: 'Mój dysk',
    computers: 'Komputery',
    documents: 'Dokumenty',
    images: 'Obrazy',
    videos: 'Filmy',
    music: 'Muzyka',
    recent: 'Ostatnie',
    starred: 'Oznaczone gwiazdką',
    trash: 'Kosz',
    storage: 'Skydysk'
  };

  const IMAGE_EXT = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg', 'avif', 'heic', 'heif', 'tiff', 'tif'];
  const VIDEO_EXT = ['mp4', 'webm', 'mkv', 'mov', 'avi', 'm4v'];
  const AUDIO_EXT = ['mp3', 'wav', 'flac', 'ogg', 'm4a', 'aac', 'opus', 'wma'];
  const ARCHIVE_EXT = ['zip', 'rar', '7z', 'tar', 'gz', 'bz2', 'xz'];
  const TEXT_EXT = ['txt', 'md', 'json', 'csv', 'log', 'xml', 'yaml', 'yml', 'html', 'css', 'js', 'ts',
    'java', 'sql', 'sh', 'bat', 'ps1', 'ini', 'cfg', 'conf', 'tex', 'rtf'];
  const DOCUMENT_EXT = ['pdf', 'doc', 'docx', 'odt', 'rtf', 'txt', 'md', 'xls', 'xlsx', 'ods', 'csv',
    'ppt', 'pptx', 'odp', 'json', 'xml', 'yaml', 'yml', 'log', 'tex'];

  const CATEGORY_EXTENSIONS = {
    documents: DOCUMENT_EXT,
    images: IMAGE_EXT,
    videos: VIDEO_EXT,
    music: AUDIO_EXT
  };

  const MIME_BY_EXT = {
    txt: 'text/plain;charset=utf-8', md: 'text/markdown;charset=utf-8', json: 'application/json',
    csv: 'text/csv;charset=utf-8', html: 'text/html;charset=utf-8', css: 'text/css;charset=utf-8',
    js: 'text/javascript;charset=utf-8', xml: 'application/xml',
    png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp',
    bmp: 'image/bmp', svg: 'image/svg+xml', avif: 'image/avif', heic: 'image/heic',
    tiff: 'image/tiff', tif: 'image/tiff',
    mp4: 'video/mp4', webm: 'video/webm', mkv: 'video/x-matroska', mov: 'video/quicktime',
    avi: 'video/x-msvideo', m4v: 'video/mp4',
    mp3: 'audio/mpeg', wav: 'audio/wav', flac: 'audio/flac', ogg: 'audio/ogg', m4a: 'audio/mp4',
    aac: 'audio/aac', opus: 'audio/opus', wma: 'audio/x-ms-wma',
    pdf: 'application/pdf',
    zip: 'application/zip', rar: 'application/vnd.rar', '7z': 'application/x-7z-compressed',
    tar: 'application/x-tar', gz: 'application/gzip', bz2: 'application/x-bzip2', xz: 'application/x-xz'
  };

  const dateFormatter = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'medium', timeStyle: 'short' });

  // --- Stan ---

  const state = {
    user: null,
    files: [],
    trash: [],
    view: 'home',
    path: '',
    search: '',
    typeFilter: 'all',
    sort: 'name-asc',
    viewMode: 'list',
    loading: false,
    inApp: false,
    selectedHash: null,
    authMode: 'login',
    sessionStartedAt: null,
    uploading: false,
    previewUrl: null
  };

  let modalResolver = null;
  let modalAllowEmpty = false;
  let menuActions = [];
  let notifyTimer = null;

  // --- Skróty DOM ---

  function $(id) {
    return document.getElementById(id);
  }

  function escapeHtml(value) {
    return String(value == null ? '' : value)
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
  }

  // --- Narzędzia ścieżek ---

  function normalizePath(value) {
    return String(value || '')
      .replaceAll('\\', '/')
      .split('/')
      .map((part) => part.trim())
      .filter((part) => part && part !== '.' && part !== '..' && !part.includes('..'))
      .join('/');
  }

  function displayName(path) {
    const normalized = normalizePath(path);
    const index = normalized.lastIndexOf('/');
    return index < 0 ? normalized : normalized.slice(index + 1);
  }

  function parentPath(path) {
    const normalized = normalizePath(path);
    const index = normalized.lastIndexOf('/');
    return index < 0 ? '' : normalized.slice(0, index);
  }

  function joinPath(folder, name) {
    const cleanFolder = normalizePath(folder);
    const cleanName = String(name || '').replaceAll('/', ' ').replaceAll('\\', ' ').trim();
    return cleanFolder ? cleanFolder + '/' + cleanName : cleanName;
  }

  function isDirectChild(path, directory) {
    const normalized = normalizePath(path);
    const current = normalizePath(directory);
    if (!current) {
      return !normalized.includes('/');
    }
    if (!normalized.startsWith(current + '/')) {
      return false;
    }
    return !normalized.slice(current.length + 1).includes('/');
  }

  function sanitizeName(name) {
    return String(name || '')
      .replace(/[\\/]+/g, ' ')
      .replace(/\s+/g, ' ')
      .trim()
      .slice(0, 180);
  }

  function extensionOf(name) {
    const value = String(name || '');
    const index = value.lastIndexOf('.');
    return index > 0 ? value.slice(index + 1).toLowerCase() : '';
  }

  // --- Formatowanie ---

  function plural(count, one, few, many) {
    if (count === 1) {
      return one;
    }
    const mod10 = count % 10;
    const mod100 = count % 100;
    if (mod10 >= 2 && mod10 <= 4 && !(mod100 >= 12 && mod100 <= 14)) {
      return few;
    }
    return many;
  }

  function formatBytes(bytes) {
    const value = Number(bytes) || 0;
    if (value < 1024) {
      return value + ' B';
    }
    const units = ['KB', 'MB', 'GB', 'TB'];
    let size = value / 1024;
    let index = 0;
    while (size >= 1024 && index < units.length - 1) {
      size /= 1024;
      index += 1;
    }
    const digits = size >= 10 ? 0 : 1;
    return size.toLocaleString('pl-PL', { maximumFractionDigits: digits }) + ' ' + units[index];
  }

  function formatDate(value) {
    if (!value) {
      return '—';
    }
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? '—' : dateFormatter.format(date);
  }

  function countLabel(count) {
    return count + ' ' + plural(count, 'element', 'elementy', 'elementów');
  }

  // --- Ikony / typy ---

  function iconFor(entry) {
    if (entry.isFolder) {
      return 'i-folder';
    }
    const ext = extensionOf(entry.name);
    if (IMAGE_EXT.includes(ext)) {
      return 'i-image-file';
    }
    if (VIDEO_EXT.includes(ext)) {
      return 'i-video-file';
    }
    if (AUDIO_EXT.includes(ext)) {
      return 'i-audio-file';
    }
    if (ARCHIVE_EXT.includes(ext)) {
      return 'i-archive-file';
    }
    if (TEXT_EXT.includes(ext) || DOCUMENT_EXT.includes(ext)) {
      return 'i-text-file';
    }
    return 'i-file';
  }

  function mimeFor(ext) {
    return MIME_BY_EXT[ext] || 'application/octet-stream';
  }

  function matchesCategory(name, view) {
    const allowed = CATEGORY_EXTENSIONS[view];
    return Boolean(allowed) && allowed.includes(extensionOf(name));
  }

  // --- API ---

  class ApiError extends Error {
    constructor(status, message) {
      super(message);
      this.status = status;
    }
  }

  async function api(url, options = {}, settings = {}) {
    const response = await fetch(url, {
      cache: 'no-store',
      credentials: 'same-origin',
      ...options
    });

    if (response.status === 401 && !settings.allow401) {
      expireSession();
      throw new ApiError(401, 'Sesja wygasła.');
    }

    if (!response.ok) {
      let message = 'Operacja nie powiodła się (' + response.status + ').';
      try {
        const payload = await response.json();
        if (payload && payload.message) {
          message = payload.message;
        }
      } catch (ignored) {
        // odpowiedź bez JSON-a
      }
      throw new ApiError(response.status, message);
    }

    if (response.status === 204) {
      return null;
    }

    const type = response.headers.get('content-type') || '';
    return type.includes('application/json') ? response.json() : response;
  }

  async function fetchProfile() {
    try {
      const response = await fetch('/api/auth/me', { cache: 'no-store', credentials: 'same-origin' });
      if (!response.ok) {
        return null;
      }
      return await response.json();
    } catch (error) {
      return null;
    }
  }

  // --- Powiadomienia ---

  function notify(message, kind = 'info') {
    let toast = $('appToast');
    if (!toast) {
      toast = document.createElement('div');
      toast.id = 'appToast';
      toast.className = 'app-toast';
      toast.setAttribute('role', 'status');
      document.body.appendChild(toast);
    }
    toast.textContent = message;
    toast.dataset.kind = kind;
    toast.hidden = false;
    clearTimeout(notifyTimer);
    notifyTimer = setTimeout(() => {
      toast.hidden = true;
    }, 4600);
  }

  // --- Ekran logowania ---

  function showAuthMessage(message) {
    const element = $('authMessage');
    element.textContent = message;
    element.hidden = false;
  }

  function hideAuthMessage() {
    $('authMessage').hidden = true;
  }

  function setAuthMode(mode) {
    state.authMode = mode === 'register' ? 'register' : 'login';
    document.querySelectorAll('[data-auth-mode]').forEach((button) => {
      button.classList.toggle('active', button.dataset.authMode === state.authMode);
    });
    $('authTitle').textContent = state.authMode === 'register' ? 'Utwórz konto' : 'Zaloguj się';
    $('authSubmit').textContent = state.authMode === 'register' ? 'Utwórz konto' : 'Zaloguj się';
    $('authPassword').autocomplete = state.authMode === 'register' ? 'new-password' : 'current-password';
    $('authHint').hidden = state.authMode !== 'register';
    $('authHint').textContent = 'Hasło chroni cały dysk. Minimum 10 znaków. Nie da się go odzyskać — zapisz kartę odzyskiwania.';
    hideAuthMessage();
  }

  function setBusy(busy, label) {
    $('authSubmit').disabled = busy;
    $('authSubmit').textContent = busy
      ? (label || 'Przetwarzanie…')
      : (state.authMode === 'register' ? 'Utwórz konto' : 'Zaloguj się');
    $('authEmail').disabled = busy;
    $('authPassword').disabled = busy;
  }

  async function handleAuthSubmit(event) {
    event.preventDefault();
    hideAuthMessage();

    const email = $('authEmail').value.trim().toLowerCase();
    const password = $('authPassword').value;

    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) {
      showAuthMessage('Podaj poprawny adres e-mail.');
      return;
    }
    if (!password) {
      showAuthMessage('Podaj hasło główne.');
      return;
    }
    if (state.authMode === 'register' && password.length < 10) {
      showAuthMessage('Hasło musi mieć co najmniej 10 znaków.');
      return;
    }

    setBusy(true, 'Wyprowadzanie klucza…');
    try {
      if (state.authMode === 'register') {
        await register(email, password);
      } else {
        await login(email, password);
      }
    } catch (error) {
      showAuthMessage(error.message || 'Nie udało się uwierzytelnić.');
    } finally {
      setBusy(false);
    }
  }

  async function register(email, password) {
    const salt = crypto.getRandomValues(new Uint8Array(16));
    const masterKey = crypto.getRandomValues(new Uint8Array(32));
    const keys = await C.deriveKeys(password, salt);
    const wrapped = await C.wrapMasterKey(keys.kek, masterKey);

    setBusy(true, 'Tworzenie konta…');
    await api('/api/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email: email,
        authVerifier: C.toBase64(keys.authVerifier),
        passwordSalt: C.toBase64(salt),
        wrappedMasterKey: C.toBase64(wrapped.ciphertext),
        wrappedMasterKeyIv: C.toBase64(wrapped.iv)
      })
    }, { allow401: true });

    C.storeMasterKey(email, masterKey);
    const profile = await api('/api/auth/me');
    await enterApp(profile);
    offerRecoverySheet(email, masterKey);
  }

  async function login(email, password) {
    const material = await api('/api/auth/crypto?email=' + encodeURIComponent(email), {}, { allow401: true });
    const keys = await C.deriveKeys(password, C.fromBase64(material.passwordSalt));

    setBusy(true, 'Logowanie…');
    await api('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: email, authVerifier: C.toBase64(keys.authVerifier) })
    }, { allow401: true });

    let masterKey;
    try {
      masterKey = await C.unwrapMasterKey(
        keys.kek,
        C.fromBase64(material.wrappedMasterKey),
        C.fromBase64(material.wrappedMasterKeyIv)
      );
    } catch (error) {
      throw new Error('Nie udało się odszyfrować klucza głównego. Spróbuj ponownie.');
    }

    C.storeMasterKey(email, masterKey);
    const profile = await api('/api/auth/me');
    await enterApp(profile);
  }

  function enterApp(profile) {
    state.user = profile;
    state.inApp = true;
    state.sessionStartedAt = Date.now();
    state.view = 'home';
    state.path = '';
    state.selectedHash = null;
    state.search = '';
    state.loading = true;
    $('searchInput').value = '';
    $('authScreen').hidden = true;
    $('appScreen').hidden = false;
    $('authPassword').value = '';
    hideAuthMessage();
    updateAccountUI();
    render();
    refreshDataSafe(true);
  }

  function resetToAuth(message) {
    state.inApp = false;
    state.user = null;
    state.files = [];
    state.trash = [];
    state.loading = false;
    state.uploading = false;
    state.selectedHash = null;
    C.clearMasterKey();
    closeContextMenu();
    $('appScreen').hidden = true;
    $('authScreen').hidden = false;
    $('uploadToast').hidden = true;
    setAuthMode('login');
    if (message) {
      showAuthMessage(message);
    }
  }

  function expireSession() {
    if (!state.inApp) {
      return;
    }
    resetToAuth('Sesja wygasła. Zaloguj się ponownie — klucz główny trzeba odtworzyć z hasła.');
  }

  async function logout() {
    try {
      await api('/api/auth/logout', { method: 'POST' });
    } catch (error) {
      // sesja mogła już wygasnąć
    }
    resetToAuth(null);
    notify('Wylogowano.');
  }

  function offerRecoverySheet(email, masterKey) {
    const safeEmail = email.replace(/[^a-z0-9@._-]/gi, '_');
    confirmAction(
      'Karta odzyskiwania',
      'Konto zostało utworzone. Pobierz kartę odzyskiwania i zachowaj ją offline — '
        + 'bez hasła i bez tej karty nie odzyskasz danych. SkyVault nie może jej odtworzyć.',
      'Pobierz kartę'
    ).then((accepted) => {
      if (!accepted) {
        return;
      }
      saveBlob(C.recoverySheet(email, masterKey), 'SkyVault-recovery-' + safeEmail + '.txt');
      notify('Pobrano kartę odzyskiwania. Zachowaj ją offline.', 'success');
    });
  }

  function downloadRecovery() {
    const masterKey = C.loadMasterKey();
    if (!masterKey) {
      notify('Brak klucza w tej karcie. Zaloguj się ponownie.', 'error');
      return;
    }
    const email = state.user ? state.user.email : C.storedEmail();
    const safeEmail = email.replace(/[^a-z0-9@._-]/gi, '_');
    saveBlob(C.recoverySheet(email, masterKey), 'SkyVault-recovery-' + safeEmail + '.txt');
    notify('Pobrano kartę odzyskiwania. Zachowaj ją offline.');
  }

  // --- Dane ---

  async function refreshData(initial = false) {
    if (initial) {
      state.loading = true;
      render();
    }

    const [profile, active, trash] = await Promise.all([
      api('/api/auth/me'),
      api('/api/files'),
      api('/api/files/trash')
    ]);

    state.user = profile;
    state.files = await decryptEntries(active);
    state.trash = await decryptEntries(trash);
    state.loading = false;
    updateAccountUI();
    render();
  }

  async function refreshDataSafe(initial = false) {
    try {
      await refreshData(initial);
    } catch (error) {
      state.loading = false;
      if (state.inApp) {
        render();
        if (!error || error.status !== 401) {
          notify((error && error.message) || 'Nie udało się wczytać danych.', 'error');
        }
      }
    }
  }

  async function refresh() {
    try {
      await refreshData(false);
      notify('Odświeżono.');
    } catch (error) {
      notify(error.message || 'Nie udało się odświeżyć.', 'error');
    }
  }

  async function decryptEntries(views) {
    const entries = [];
    for (const view of views) {
      let path = '';
      try {
        path = normalizePath(await C.decryptPath(view.encryptedVirtualPath, view.virtualPathIv));
      } catch (error) {
        path = 'nieczytelna-' + String(view.pathHash).slice(0, 8);
      }
      entries.push({
        id: view.id,
        pathHash: view.pathHash,
        encryptedVirtualPath: view.encryptedVirtualPath,
        virtualPathIv: view.virtualPathIv,
        blobId: view.blobId,
        sizeBytes: view.sizeBytes || 0,
        storedSizeBytes: view.storedSizeBytes || 0,
        isFolder: Boolean(view.folder),
        isTrashed: Boolean(view.inTrash),
        trashedAt: view.trashedAt,
        starred: Boolean(view.starred),
        modifiedAt: view.modifiedAt,
        path: path,
        name: displayName(path) || path
      });
    }
    return entries;
  }

  function findEntry(hash) {
    return state.files.find((entry) => entry.pathHash === hash)
      || state.trash.find((entry) => entry.pathHash === hash)
      || null;
  }

  function currentEntries() {
    let entries;

    if (state.view === 'trash') {
      entries = state.trash.slice();
    } else if (state.view === 'starred') {
      entries = state.files.filter((entry) => entry.starred);
    } else if (state.view === 'recent') {
      entries = state.files.filter((entry) => !entry.isFolder);
    } else if (CATEGORY_EXTENSIONS[state.view]) {
      entries = state.files.filter((entry) => !entry.isFolder && matchesCategory(entry.name, state.view));
    } else {
      entries = state.files.filter((entry) => isDirectChild(entry.path, state.path));
    }

    if (state.view === 'home') {
      if (state.typeFilter === 'folder') {
        entries = entries.filter((entry) => entry.isFolder);
      } else if (state.typeFilter === 'file') {
        entries = entries.filter((entry) => !entry.isFolder);
      }
    }

    const query = state.search.trim().toLowerCase();
    if (query) {
      entries = entries.filter((entry) =>
        entry.name.toLowerCase().includes(query) || entry.path.toLowerCase().includes(query));
    }

    entries = sortEntries(entries);

    if (state.view === 'recent') {
      entries = entries.slice(0, 60);
    }
    return entries;
  }

  function sortEntries(entries) {
    const key = state.sort.split('-')[0];
    const direction = state.sort.endsWith('desc') ? -1 : 1;

    return entries.slice().sort((a, b) => {
      if (state.view === 'home' && a.isFolder !== b.isFolder) {
        return a.isFolder ? -1 : 1;
      }
      let result = 0;
      if (key === 'name') {
        result = a.name.localeCompare(b.name, 'pl', { sensitivity: 'base', numeric: true });
      } else if (key === 'size') {
        result = (a.sizeBytes || 0) - (b.sizeBytes || 0);
      } else {
        const aDate = state.view === 'trash' ? (a.trashedAt || a.modifiedAt) : a.modifiedAt;
        const bDate = state.view === 'trash' ? (b.trashedAt || b.modifiedAt) : b.modifiedAt;
        result = new Date(aDate || 0).getTime() - new Date(bDate || 0).getTime();
      }
      return result * direction;
    });
  }

  // --- Render ---

  function render() {
    if (!state.inApp) {
      return;
    }

    updateAccountUI();
    updateToolbar();
    updateNav();
    updateBreadcrumbs();
    $('workspaceTitle').textContent = viewTitle();

    if (state.view === 'storage') {
      $('filesSurface').hidden = true;
      $('storageView').hidden = false;
      $('computersView').hidden = true;
      renderStorage();
      return;
    }

    if (state.view === 'computers') {
      $('filesSurface').hidden = true;
      $('storageView').hidden = true;
      $('computersView').hidden = false;
      renderComputers();
      return;
    }

    $('storageView').hidden = true;
    $('computersView').hidden = true;
    $('filesSurface').hidden = false;

    if (state.loading) {
      $('fileCount').textContent = 'Wczytywanie…';
      $('filesSurface').innerHTML = '<div class="empty-state"><p>Odszyfrowywanie zawartości dysku…</p></div>';
      return;
    }

    const entries = currentEntries();
    $('fileCount').textContent = countLabel(entries.length);
    renderFiles(entries);
  }

  function viewTitle() {
    if (state.view === 'home' && state.path) {
      return displayName(state.path);
    }
    return VIEW_TITLES[state.view] || 'SkyVault';
  }

  function updateToolbar() {
    const fileView = state.view !== 'storage' && state.view !== 'computers';
    $('filters').hidden = !fileView || state.view === 'trash';
    $('sectionHead').hidden = !fileView;
    const locationRow = document.querySelector('.location-row');
    if (locationRow) {
      locationRow.hidden = !fileView;
    }
    $('typeFilterLabel').hidden = state.view !== 'home';
    $('newFolderButton').hidden = state.view !== 'home';
    $('uploadButton').hidden = !(state.view === 'home'
      || state.view === 'documents'
      || state.view === 'images'
      || state.view === 'videos'
      || state.view === 'music'
      || state.view === 'recent'
      || state.view === 'starred');
    $('emptyTrashButton').hidden = state.view !== 'trash';
  }

  function updateNav() {
    document.querySelectorAll('.nav-item[data-view]').forEach((button) => {
      button.classList.toggle('active', button.dataset.view === state.view);
    });
  }

  function updateBreadcrumbs() {
    const container = $('breadcrumbs');
    container.hidden = state.view !== 'home';
    container.innerHTML = '';

    if (state.view !== 'home') {
      return;
    }

    const root = document.createElement('button');
    root.type = 'button';
    root.dataset.crumb = '';
    root.textContent = 'Mój dysk';
    root.className = state.path ? '' : 'current';
    container.appendChild(root);

    if (!state.path) {
      return;
    }

    const parts = state.path.split('/');
    let cumulative = '';
    parts.forEach((part, index) => {
      cumulative = cumulative ? cumulative + '/' + part : part;
      const separator = document.createElement('span');
      separator.className = 'separator';
      separator.textContent = '/';
      container.appendChild(separator);

      const button = document.createElement('button');
      button.type = 'button';
      button.dataset.crumb = cumulative;
      button.textContent = part;
      if (index === parts.length - 1) {
        button.className = 'current';
      }
      container.appendChild(button);
    });
  }

  function updateAccountUI() {
    const user = state.user;
    const initial = user && user.email ? user.email.charAt(0).toUpperCase() : '?';
    $('accountButton').textContent = initial;

    if (!user) {
      return;
    }

    const used = Number(user.usedBytes) || 0;
    const quota = Number(user.quotaBytes) || 0;
    const percent = quota > 0 ? Math.min(100, (used / quota) * 100) : 0;
    const usage = formatBytes(used) + ' z ' + formatBytes(quota) + ' (' + percent.toFixed(1) + '%)';

    $('accountEmail').textContent = user.email;
    $('accountUsage').textContent = usage;
    $('sidebarUsage').textContent = formatBytes(used) + ' z ' + formatBytes(quota);
    $('settingsUsage').textContent = usage;
    $('sidebarMeter').style.width = percent + '%';
    $('accountMeter').style.width = percent + '%';
    $('settingsMeter').style.width = percent + '%';
  }

  function renderFiles(entries) {
    const surface = $('filesSurface');
    surface.dataset.viewMode = state.viewMode;

    if (!entries.length) {
      surface.innerHTML = emptyState();
      return;
    }

    surface.innerHTML = state.viewMode === 'grid' ? gridMarkup(entries) : tableMarkup(entries);
  }

  function emptyState() {
    let title = 'Ten folder jest pusty.';
    let hint = 'Przeciągnij pliki tutaj, aby je zaszyfrować i wysłać. '
      + 'Kliknij prawym przyciskiem, aby utworzyć folder albo wysłać cały katalog.';
    if (state.search.trim()) {
      title = 'Brak wyników dla „' + state.search.trim() + '”.';
      hint = 'Zmień frazę lub wyczyść pole wyszukiwania.';
    } else if (state.view === 'trash') {
      title = 'Kosz jest pusty.';
      hint = 'Elementy przeniesione do kosza można tu przywrócić lub usunąć trwale.';
    } else if (state.view === 'starred') {
      title = 'Nic nie jest oznaczone gwiazdką.';
      hint = 'Użyj menu kontekstowego pliku, aby dodać gwiazdkę.';
    } else if (state.view === 'recent') {
      title = 'Brak ostatnio zmodyfikowanych plików.';
      hint = 'Widok pokazuje 60 najnowszych plików z całego dysku.';
    } else if (state.view === 'documents') {
      title = 'Brak dokumentów.';
      hint = 'Dokumenty (PDF, tekst, arkusze) pojawią się tutaj automatycznie.';
    } else if (state.view === 'images') {
      title = 'Brak obrazów.';
      hint = 'Obrazy pojawią się tutaj automatycznie po przesłaniu.';
    } else if (state.view === 'videos') {
      title = 'Brak filmów.';
      hint = 'Filmy pojawią się tutaj automatycznie po przesłaniu.';
    } else if (state.view === 'music') {
      title = 'Brak muzyki.';
      hint = 'Pliki audio pojawią się tutaj automatycznie po przesłaniu.';
    }

    return '<div class="empty-state"><svg class="icon"><use href="#i-folder"></use></svg>'
      + '<p>' + escapeHtml(title) + '</p>'
      + '<p class="empty-hint">' + escapeHtml(hint) + '</p></div>';
  }

  function tableMarkup(entries) {
    const rows = entries.map((entry) => {
      const icon = iconFor(entry);
      const selected = entry.pathHash === state.selectedHash ? ' is-selected' : '';
      const parts = entry.path.split('/');
      const location = state.view === 'home' || parts.length < 2
        ? ''
        : parts.slice(0, -1).join(' / ');

      return '<tr class="file-row' + selected + '" data-hash="' + entry.pathHash + '" draggable="true">'
        + '<td><div class="file-name">'
        + '<svg class="icon"><use href="#' + icon + '"></use></svg>'
        + '<div class="file-text">'
        + '<button type="button" class="file-link" data-select="' + entry.pathHash + '" title="'
        + escapeHtml(entry.path) + '">' + escapeHtml(entry.name) + '</button>'
        + (location ? '<span class="file-location">' + escapeHtml(location) + '</span>' : '')
        + '</div>'
        + (entry.starred ? '<svg class="icon star-mark"><use href="#i-star"></use></svg>' : '')
        + (entry.isTrashed ? '<span class="trash-chip">Kosz</span>' : '')
        + '</div></td>'
        + '<td><span class="owner-chip"><span class="owner-avatar">' + escapeHtml(userInitial())
        + '</span><span class="owner-name">Ja</span></span></td>'
        + '<td class="nowrap">' + formatDate(entry.modifiedAt) + '</td>'
        + '<td class="nowrap">' + (entry.isFolder ? '—' : formatBytes(entry.sizeBytes)) + '</td>'
        + '<td class="row-actions"><button type="button" class="icon-button row-menu" data-menu="'
        + entry.pathHash + '" aria-label="Więcej"><svg class="icon"><use href="#i-dots"></use></svg></button></td>'
        + '</tr>';
    }).join('');

    return '<table class="files-table"><thead><tr>'
      + '<th>Nazwa</th><th>Właściciel</th><th>Modyfikacja</th><th>Rozmiar</th><th></th>'
      + '</tr></thead><tbody>' + rows + '</tbody></table>';
  }

  function gridMarkup(entries) {
    const cards = entries.map((entry) => {
      const icon = iconFor(entry);
      const selected = entry.pathHash === state.selectedHash ? ' is-selected' : '';
      const parts = entry.path.split('/');
      const location = state.view === 'home' || parts.length < 2
        ? ''
        : parts.slice(0, -1).join(' / ');
      const meta = entry.isFolder
        ? 'Folder' + (location ? ' · ' + location : '')
        : formatBytes(entry.sizeBytes) + (location ? ' · ' + location : '');

      return '<div class="file-card' + selected + '" data-hash="' + entry.pathHash + '" draggable="true">'
        + '<div class="file-card-preview"><svg class="icon"><use href="#' + icon + '"></use></svg></div>'
        + '<div class="file-card-body">'
        + '<button type="button" class="file-card-name" data-select="' + entry.pathHash + '" title="'
        + escapeHtml(entry.path) + '">' + escapeHtml(entry.name) + '</button>'
        + '<span class="file-card-meta">' + escapeHtml(meta) + '</span>'
        + '</div></div>';
    }).join('');

    return '<div class="files-grid">' + cards + '</div>';
  }

  function userInitial() {
    return state.user && state.user.email ? state.user.email.charAt(0).toUpperCase() : '?';
  }

  function updateSelection() {
    document.querySelectorAll('#filesSurface [data-hash]').forEach((element) => {
      element.classList.toggle('is-selected', element.dataset.hash === state.selectedHash);
    });
  }

  // --- Skydysk / Komputery ---

  function renderStorage() {
    const user = state.user;
    const files = state.files.filter((entry) => !entry.isFolder);
    const folders = state.files.filter((entry) => entry.isFolder);
    const used = user ? Number(user.usedBytes) || 0 : 0;
    const quota = user ? Number(user.quotaBytes) || 0 : 0;
    const percent = quota > 0 ? Math.min(100, (used / quota) * 100) : 0;
    const plainTotal = files.reduce((sum, entry) => sum + (entry.sizeBytes || 0), 0);
    const storedTotal = files.reduce((sum, entry) => sum + (entry.storedSizeBytes || 0), 0);
    const overhead = Math.max(0, storedTotal - plainTotal);
    const top = files.slice().sort((a, b) => b.sizeBytes - a.sizeBytes).slice(0, 5);

    const topRows = top.length
      ? top.map((entry) => '<div class="storage-row"><span>' + escapeHtml(entry.name)
        + '</span><span>' + formatBytes(entry.sizeBytes) + '</span></div>').join('')
      : '<p class="muted">Brak plików.</p>';

    $('storageView').innerHTML =
      '<div class="storage-overview">'
      + '<div class="storage-main-stat"><span>Wykorzystanie Skydysku</span><strong>'
      + formatBytes(used) + '<small> / ' + formatBytes(quota) + '</small></strong></div>'
      + '<div class="meter"><span style="width:' + percent + '%"></span></div>'
      + '<p class="storage-percent">' + percent.toFixed(1) + '% limitu · pozostało '
      + formatBytes(Math.max(0, quota - used)) + '</p>'
      + '</div>'
      + '<div class="storage-stats">'
      + '<div><span>Pliki</span><strong>' + files.length + '</strong></div>'
      + '<div><span>Foldery</span><strong>' + folders.length + '</strong></div>'
      + '<div><span>W koszu</span><strong>' + state.trash.length + '</strong></div>'
      + '<div><span>Zaszyfrowane</span><strong>' + formatBytes(storedTotal) + '</strong></div>'
      + '</div>'
      + '<div class="storage-block"><h2>Największe pliki</h2>' + topRows + '</div>'
      + '<div class="storage-block"><h2>Narzut szyfrowania</h2>'
      + '<div class="storage-row"><span>Jawne dane</span><span>' + formatBytes(plainTotal) + '</span></div>'
      + '<div class="storage-row"><span>Zaszyfrowane bloby</span><span>' + formatBytes(storedTotal) + '</span></div>'
      + '<div class="storage-row"><span>IV + tag + nagłówek</span><span>' + formatBytes(overhead) + '</span></div>'
      + '</div>';
  }

  function browserInfo() {
    const ua = navigator.userAgent;
    let browser = 'Przeglądarka';
    let system = 'System';

    if (/Edg\//.test(ua)) {
      browser = 'Microsoft Edge';
    } else if (/Firefox\//.test(ua)) {
      browser = 'Firefox';
    } else if (/Chrome\//.test(ua)) {
      browser = 'Chrome';
    } else if (/Safari\//.test(ua)) {
      browser = 'Safari';
    }

    if (/Windows/.test(ua)) {
      system = 'Windows';
    } else if (/Android/.test(ua)) {
      system = 'Android';
    } else if (/iPhone|iPad|iPod/.test(ua)) {
      system = 'iOS';
    } else if (/Mac OS X/.test(ua)) {
      system = 'macOS';
    } else if (/Linux/.test(ua)) {
      system = 'Linux';
    }

    return { browser: browser, system: system };
  }

  function renderComputers() {
    const info = browserInfo();
    const started = state.sessionStartedAt ? formatDate(new Date(state.sessionStartedAt).toISOString()) : '—';

    $('computersView').innerHTML =
      '<div class="device-card">'
      + '<div class="device-identity"><svg class="icon"><use href="#i-computers"></use></svg>'
      + '<div><strong>' + escapeHtml(info.browser) + ' na ' + escapeHtml(info.system) + '</strong>'
      + '<span>Ta przeglądarka · sesja aktywna</span></div></div>'
      + '<div><span class="device-label">Zalogowano</span><strong>' + escapeHtml(started) + '</strong></div>'
      + '<div><span class="device-label">Klucz</span><strong>w pamięci karty</strong></div>'
      + '<div class="device-status">Online</div>'
      + '</div>'
      + '<div class="storage-block">'
      + '<h2>Jak działają urządzenia</h2>'
      + '<p class="muted">SkyVault nie przechowuje listy urządzeń ani adresów IP w tym widoku. '
      + 'Każda przeglądarka odblokowuje dysk osobno: po zalogowaniu klucz główny jest odtwarzany '
      + 'z hasła wyłącznie lokalnie i trzymany w pamięci tej karty. Zamknięcie karty zapomina klucz.</p>'
      + '<p class="muted">Wylogowanie unieważnia sesję po stronie serwera.</p>'
      + '</div>';
  }

  // --- Nawigacja ---

  function setView(view, path) {
    state.view = view;
    state.path = view === 'home' ? normalizePath(path) : '';
    state.search = '';
    state.selectedHash = null;
    $('searchInput').value = '';

    if (view === 'recent' || view === 'trash') {
      state.sort = 'modified-desc';
      $('sortSelect').value = state.sort;
    }

    closeContextMenu();
    $('accountPanel').hidden = true;
    $('settingsPanel').hidden = true;
    render();
    document.querySelector('.workspace').scrollTop = 0;
  }

  function setViewMode(mode) {
    state.viewMode = mode === 'grid' ? 'grid' : 'list';
    localStorage.setItem('skyvaultViewMode', state.viewMode);
    document.querySelectorAll('.view-button[data-view-mode]').forEach((button) => {
      button.classList.toggle('active', button.dataset.viewMode === state.viewMode);
    });
    const surface = $('filesSurface');
    if (surface) {
      surface.dataset.viewMode = state.viewMode;
    }
    if (state.inApp) {
      render();
    }
  }

  function uploadFolder() {
    return state.view === 'home' ? state.path : '';
  }

  // --- Operacje na plikach ---

  async function openEntry(entry) {
    if (entry.isFolder) {
      setView('home', entry.path);
      return;
    }
    await previewEntry(entry);
  }

  async function toggleStar(entry) {
    try {
      await api('/api/files/' + entry.pathHash + '/star', { method: 'POST' });
      await refreshData(false);
      notify(entry.starred ? 'Usunięto gwiazdkę.' : 'Dodano gwiazdkę.');
    } catch (error) {
      notify(error.message, 'error');
    }
  }

  function collectWithDescendants(entry, list) {
    if (!entry.isFolder) {
      return [entry];
    }
    return [entry].concat(list.filter((candidate) =>
      candidate.pathHash !== entry.pathHash && candidate.path.startsWith(entry.path + '/')));
  }

  async function trashEntry(entry) {
    const children = collectWithDescendants(entry, state.files);
    const confirmed = await confirmAction(
      'Przenieść do kosza?',
      '„' + entry.name + '”' + (entry.isFolder ? ' wraz z zawartością (' + (children.length - 1) + ' elementów)' : '')
      + ' trafi do kosza. Możesz go później przywrócić.',
      'Przenieś do kosza'
    );
    if (!confirmed) {
      return;
    }

    try {
      for (const item of children) {
        await api('/api/files/' + item.pathHash + '/trash', { method: 'POST' });
      }
      state.selectedHash = null;
      await refreshData(false);
      notify('Przeniesiono do kosza.');
    } catch (error) {
      notify(error.message, 'error');
      await refreshDataSafe(false);
    }
  }

  async function restoreEntry(entry) {
    const children = collectWithDescendants(entry, state.trash);
    try {
      for (const item of children) {
        await api('/api/files/' + item.pathHash + '/restore', { method: 'POST' });
      }
      state.selectedHash = null;
      await refreshData(false);
      notify('Przywrócono z kosza.');
    } catch (error) {
      notify(error.message, 'error');
      await refreshDataSafe(false);
    }
  }

  async function deleteEntry(entry) {
    const children = collectWithDescendants(entry, state.trash);
    const confirmed = await confirmAction(
      'Usunąć trwale?',
      '„' + entry.name + '”' + (entry.isFolder ? ' i cała zawartość' : '')
      + ' zostanie usunięty bez możliwości przywrócenia.',
      'Usuń trwale'
    );
    if (!confirmed) {
      return;
    }

    children.sort((a, b) => b.path.split('/').length - a.path.split('/').length);
    try {
      for (const item of children) {
        await api('/api/files/' + item.pathHash, { method: 'DELETE' });
      }
      state.selectedHash = null;
      await refreshData(false);
      notify('Usunięto trwale.');
    } catch (error) {
      notify(error.message, 'error');
      await refreshDataSafe(false);
    }
  }

  async function emptyTrash() {
    if (!state.trash.length) {
      return;
    }
    const confirmed = await confirmAction(
      'Opróżnić kosz?',
      'Wszystkie elementy w koszu (' + state.trash.length + ') zostaną usunięte bez możliwości przywrócenia.',
      'Opróżnij kosz'
    );
    if (!confirmed) {
      return;
    }

    const items = state.trash.slice().sort((a, b) => b.path.split('/').length - a.path.split('/').length);
    try {
      for (const item of items) {
        await api('/api/files/' + item.pathHash, { method: 'DELETE' });
      }
      state.selectedHash = null;
      await refreshData(false);
      notify('Kosz opróżniony.');
    } catch (error) {
      notify(error.message, 'error');
      await refreshDataSafe(false);
    }
  }

  async function moveEntryTo(entry, newPath) {
    const target = normalizePath(newPath);
    if (!target || target === entry.path) {
      return;
    }
    if (entry.isFolder && (target === entry.path || target.startsWith(entry.path + '/'))) {
      notify('Nie można przenieść folderu do niego samego.', 'error');
      return;
    }

    const affected = collectWithDescendants(entry, state.files);
    const mappings = affected.map((item) => ({
      item: item,
      newPath: target + item.path.slice(entry.path.length)
    }));

    const affectedHashes = new Set(affected.map((item) => item.pathHash));
    const lowered = new Set(state.files
      .filter((item) => !affectedHashes.has(item.pathHash))
      .map((item) => item.path.toLowerCase()));
    if (mappings.some((mapping) => lowered.has(mapping.newPath.toLowerCase()))) {
      notify('W folderze docelowym istnieje już element o tej nazwie.', 'error');
      return;
    }

    try {
      for (const mapping of mappings) {
        const meta = await C.encryptPath(mapping.newPath);
        await api('/api/files/' + mapping.item.pathHash + '/move', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            pathHash: meta.pathHash,
            encryptedVirtualPath: meta.encryptedVirtualPath,
            virtualPathIv: meta.virtualPathIv
          })
        });
      }
      await refreshData(false);
      notify('Przeniesiono.');
    } catch (error) {
      notify(error.message, 'error');
      await refreshDataSafe(false);
    }
  }

  async function renameEntry(entry) {
    const value = await askText('Zmień nazwę', 'Podaj nową nazwę dla „' + entry.name + '”.', entry.name);
    if (!value || value === entry.name) {
      return;
    }
    if (/[\\/]/.test(value)) {
      notify('Nazwa nie może zawierać ukośników.', 'error');
      return;
    }
    await moveEntryTo(entry, joinPath(parentPath(entry.path), sanitizeName(value)));
  }

  async function moveEntryDialog(entry) {
    const value = await askText(
      'Przenieś',
      'Podaj folder docelowy. Puste pole oznacza folder główny.',
      parentPath(entry.path),
      'np. Dokumenty/2026',
      true
    );
    if (value === null) {
      return;
    }
    const folder = normalizePath(value);
    await moveEntryTo(entry, joinPath(folder, entry.name));
  }

  async function moveInto(source, targetFolder) {
    if (source.pathHash === targetFolder.pathHash) {
      return;
    }
    if (parentPath(source.path) === targetFolder.path) {
      return;
    }
    await moveEntryTo(source, joinPath(targetFolder.path, source.name));
  }

  async function createFolder() {
    const folder = uploadFolder();
    const value = await askText('Nowy folder', 'Podaj nazwę folderu.', '', 'Nowy folder');
    if (value === null) {
      return;
    }
    const name = sanitizeName(value);
    if (!name) {
      return;
    }

    const reserved = new Set(state.files.map((entry) => entry.path.toLowerCase()));
    const path = uniquePath(folder, name, reserved);

    try {
      const meta = await C.encryptPath(path);
      await api('/api/folders', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          pathHash: meta.pathHash,
          encryptedVirtualPath: meta.encryptedVirtualPath,
          virtualPathIv: meta.virtualPathIv
        })
      });
      await refreshData(false);
      notify('Utworzono folder „' + displayName(path) + '”.');
    } catch (error) {
      notify(error.message, 'error');
    }
  }

  function uniquePath(folder, name, reserved) {
    const clean = sanitizeName(name) || 'plik';
    const existing = reserved || new Set(state.files.map((entry) => entry.path.toLowerCase()));
    const direct = joinPath(folder, clean);
    if (!existing.has(direct.toLowerCase())) {
      return direct;
    }

    const dot = clean.lastIndexOf('.');
    const base = dot > 0 ? clean.slice(0, dot) : clean;
    const extension = dot > 0 ? clean.slice(dot) : '';

    for (let index = 1; index < 1000; index += 1) {
      const candidate = joinPath(folder, base + ' (' + index + ')' + extension);
      if (!existing.has(candidate.toLowerCase())) {
        return candidate;
      }
    }
    return joinPath(folder, base + ' (' + Date.now() + ')' + extension);
  }

  // --- Pobieranie i podgląd ---

  function saveBlob(blob, filename) {
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 5000);
  }

  async function fetchPlain(entry) {
    const response = await api('/api/files/' + entry.pathHash + '/content');
    const buffer = await response.arrayBuffer();
    return C.decryptBlob(buffer);
  }

  async function downloadEntry(entry) {
    try {
      notify('Odszyfrowywanie „' + entry.name + '”…');
      const plain = await fetchPlain(entry);
      const ext = extensionOf(entry.name);
      saveBlob(new Blob([plain], { type: mimeFor(ext) }), entry.name);
      notify('Pobrano „' + entry.name + '”.');
    } catch (error) {
      notify(error.message, 'error');
    }
  }

  function releasePreviewUrl() {
    if (state.previewUrl) {
      URL.revokeObjectURL(state.previewUrl);
      state.previewUrl = null;
    }
  }

  async function previewEntry(entry) {
    $('previewTitle').textContent = entry.name;
    $('previewBody').innerHTML = '<p class="preview-note">Odszyfrowywanie w przeglądarce…</p>';
    $('previewModal').hidden = false;

    try {
      const plain = await fetchPlain(entry);
      const ext = extensionOf(entry.name);
      releasePreviewUrl();

      if (IMAGE_EXT.includes(ext)) {
        state.previewUrl = URL.createObjectURL(new Blob([plain], { type: mimeFor(ext) }));
        $('previewBody').innerHTML = '<img src="' + state.previewUrl + '" alt="">';
      } else if (VIDEO_EXT.includes(ext)) {
        state.previewUrl = URL.createObjectURL(new Blob([plain], { type: mimeFor(ext) }));
        $('previewBody').innerHTML = '<video controls src="' + state.previewUrl + '"></video>';
      } else if (AUDIO_EXT.includes(ext)) {
        state.previewUrl = URL.createObjectURL(new Blob([plain], { type: mimeFor(ext) }));
        $('previewBody').innerHTML = '<audio controls src="' + state.previewUrl + '"></audio>';
      } else if (ext === 'pdf') {
        state.previewUrl = URL.createObjectURL(new Blob([plain], { type: 'application/pdf' }));
        $('previewBody').innerHTML = '<iframe class="preview-frame" src="' + state.previewUrl + '" title="PDF"></iframe>';
      } else if (TEXT_EXT.includes(ext) || isProbablyText(plain)) {
        const slice = plain.length > PREVIEW_TEXT_LIMIT ? plain.subarray(0, PREVIEW_TEXT_LIMIT) : plain;
        const text = new TextDecoder('utf-8', { fatal: false }).decode(slice);
        $('previewBody').innerHTML = '<pre>' + escapeHtml(text)
          + (plain.length > slice.length ? '\n\n… podgląd skrócony' : '') + '</pre>';
      } else {
        $('previewBody').innerHTML = '<p class="preview-note">Brak podglądu dla tego typu pliku. Pobierz plik, aby go otworzyć.</p>';
      }
    } catch (error) {
      $('previewBody').innerHTML = '<p class="preview-note preview-error">' + escapeHtml(error.message) + '</p>';
    }
  }

  function isProbablyText(bytes) {
    const sample = bytes.subarray(0, Math.min(bytes.length, 512));
    if (!sample.length) {
      return true;
    }
    let suspicious = 0;
    for (const byte of sample) {
      if (byte === 0) {
        return false;
      }
      if (byte < 9 || (byte > 13 && byte < 32)) {
        suspicious += 1;
      }
    }
    return suspicious / sample.length < 0.05;
  }

  function closePreview() {
    releasePreviewUrl();
    $('previewBody').innerHTML = '';
    $('previewModal').hidden = true;
  }

  // --- Upload ---

  function uploadFiles(files, folder) {
    const list = Array.from(files || []);
    if (!list.length) {
      return;
    }
    if (state.view !== 'home') {
      notify('Pliki zostaną dodane do folderu głównego.');
    }
    performUpload(list.map((file) => ({ file: file, folder: folder })));
  }

  async function handleFolderUpload(fileList) {
    const files = Array.from(fileList || []);
    if (!files.length) {
      return;
    }

    const base = uploadFolder();
    const known = new Set(state.files.map((entry) => entry.path.toLowerCase()));
    const foldersToCreate = new Set();

    for (const file of files) {
      const parts = String(file.webkitRelativePath || file.name).split('/').filter(Boolean);
      let current = base;
      for (const directory of parts.slice(0, -1)) {
        const next = joinPath(current, directory);
        if (!known.has(next.toLowerCase())) {
          foldersToCreate.add(next);
          known.add(next.toLowerCase());
        }
        current = next;
      }
    }

    if (foldersToCreate.size) {
      try {
        const ordered = Array.from(foldersToCreate)
          .sort((a, b) => a.split('/').length - b.split('/').length);
        for (const path of ordered) {
          const meta = await C.encryptPath(path);
          await api('/api/folders', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              pathHash: meta.pathHash,
              encryptedVirtualPath: meta.encryptedVirtualPath,
              virtualPathIv: meta.virtualPathIv
            })
          });
        }
        await refreshData(false);
      } catch (error) {
        notify(error.message, 'error');
        return;
      }
    }

    const items = files.map((file) => {
      const parts = String(file.webkitRelativePath || file.name).split('/').filter(Boolean);
      return { file: file, folder: joinPath(base, parts.slice(0, -1).join('/')) };
    });

    performUpload(items);
  }

  function showUploadToast(items) {
    $('uploadToast').hidden = false;
    $('uploadTitle').textContent = 'Przesyłanie ' + items.length + ' '
      + plural(items.length, 'pliku', 'plików', 'plików');
    $('uploadSubtitle').textContent = 'Pliki są szyfrowane w tej karcie przed wysłaniem.';
    $('uploadMeter').style.width = '0%';
    $('uploadQueue').innerHTML = items.map((item, index) =>
      '<div class="upload-item" data-upload-index="' + index + '" data-status="pending">'
      + '<span class="upload-item-name" title="' + escapeHtml(item.file.name) + '">'
      + escapeHtml(item.file.name) + '</span>'
      + '<span class="upload-item-detail">Oczekuje…</span></div>').join('');
  }

  function setUploadItem(index, status, detail) {
    const node = $('uploadQueue').querySelector('[data-upload-index="' + index + '"]');
    if (!node) {
      return;
    }
    node.dataset.status = status;
    const label = node.querySelector('.upload-item-detail');
    if (label) {
      label.textContent = detail;
    }
  }

  function updateUploadProgress(done, total) {
    $('uploadMeter').style.width = total ? Math.round((done / total) * 100) + '%' : '0%';
    $('uploadSubtitle').textContent = done + ' z ' + total + ' · szyfrowanie AES-GCM w przeglądarce';
  }

  async function performUpload(items) {
    if (!items.length) {
      return;
    }
    if (state.uploading) {
      notify('Trwa już przesyłanie plików. Poczekaj na zakończenie.', 'error');
      return;
    }

    state.uploading = true;
    const total = items.length;
    const reserved = new Set(state.files.map((entry) => entry.path.toLowerCase()));
    const quota = state.user ? Number(state.user.quotaBytes) || 0 : 0;
    let projectedUsed = state.user ? Number(state.user.usedBytes) || 0 : 0;
    let succeeded = 0;
    let failed = 0;

    showUploadToast(items);
    updateUploadProgress(0, total);

    for (let index = 0; index < items.length; index += 1) {
      const item = items[index];
      const file = item.file;
      const path = uniquePath(item.folder, file.name, reserved);
      reserved.add(path.toLowerCase());

      try {
        if (file.size > MAX_UPLOAD_BYTES) {
          throw new Error('Plik przekracza 100 MB.');
        }
        if (quota > 0 && projectedUsed + file.size > quota) {
          throw new Error('Przekroczono limit miejsca na koncie.');
        }
        if (!C.loadMasterKey()) {
          throw new Error('Brak klucza w tej karcie.');
        }

        setUploadItem(index, 'working', 'Szyfrowanie…');
        const meta = await C.encryptPath(path);
        const encrypted = await C.encryptBlob(await file.arrayBuffer());

        setUploadItem(index, 'working', 'Przesyłanie…');
        const form = new FormData();
        form.append('pathHash', meta.pathHash);
        form.append('encryptedVirtualPath', meta.encryptedVirtualPath);
        form.append('virtualPathIv', meta.virtualPathIv);
        form.append('sizeBytes', String(file.size));
        form.append('blob', encrypted, 'blob.bin');

        await api('/api/files', { method: 'POST', body: form });
        projectedUsed += file.size;
        succeeded += 1;
        setUploadItem(index, 'done', 'Zaszyfrowano i wysłano');
      } catch (error) {
        failed += 1;
        setUploadItem(index, 'error', error.message || 'Błąd przesyłania');
      }

      updateUploadProgress(index + 1, total);
    }

    state.uploading = false;
    $('uploadTitle').textContent = failed ? 'Przesyłanie zakończone' : 'Przesłano pliki';
    $('uploadSubtitle').textContent = 'Gotowe: ' + succeeded + ' · błędy: ' + failed;

    if (succeeded) {
      await refreshDataSafe(false);
    }
    if (!failed) {
      setTimeout(() => {
        if (!state.uploading) {
          $('uploadToast').hidden = true;
        }
      }, 4600);
    }
  }

  // --- Menu kontekstowe ---

  function openContextMenu(entry, anchor) {
    const menu = $('contextMenu');
    $('accountPanel').hidden = true;
    menuActions = [];

    if (!entry) {
      menuActions = [
        { label: 'Nowy folder', action: createFolder },
        { label: 'Prześlij pliki', action: () => $('fileInput').click() },
        { label: 'Prześlij folder', action: () => $('folderInput').click() },
        { label: 'Odśwież', action: refresh }
      ];
    } else if (entry.isTrashed) {
      menuActions = [
        { label: 'Przywróć', action: () => restoreEntry(entry) },
        { label: 'Usuń trwale', action: () => deleteEntry(entry) },
        { label: 'Informacje', action: () => showInfo(entry) }
      ];
    } else {
      menuActions = [
        { label: entry.isFolder ? 'Otwórz' : 'Podgląd', action: () => openEntry(entry) }
      ];
      if (!entry.isFolder) {
        menuActions.push({ label: 'Pobierz', action: () => downloadEntry(entry) });
      }
      menuActions.push({ label: entry.starred ? 'Usuń gwiazdkę' : 'Dodaj gwiazdkę', action: () => toggleStar(entry) });
      menuActions.push({ label: 'Zmień nazwę', action: () => renameEntry(entry) });
      menuActions.push({ label: 'Przenieś', action: () => moveEntryDialog(entry) });
      menuActions.push({ label: 'Informacje', action: () => showInfo(entry) });
      menuActions.push({ label: 'Przenieś do kosza', action: () => trashEntry(entry) });
    }

    menu.innerHTML = menuActions.map((item, index) =>
      '<button type="button" role="menuitem" data-index="' + index + '">'
      + escapeHtml(item.label) + '</button>').join('');
    menu.hidden = false;

    const isElement = anchor instanceof Element;
    const rect = isElement
      ? anchor.getBoundingClientRect()
      : { left: anchor.x, right: anchor.x, top: anchor.y, bottom: anchor.y };

    let left = isElement ? rect.right - menu.offsetWidth : rect.left;
    let top = rect.bottom + 6;
    const margin = 8;

    if (left + menu.offsetWidth > window.innerWidth - margin) {
      left = window.innerWidth - menu.offsetWidth - margin;
    }
    if (top + menu.offsetHeight > window.innerHeight - margin) {
      top = Math.max(margin, rect.top - menu.offsetHeight - 4);
    }

    menu.style.left = Math.max(margin, left) + 'px';
    menu.style.top = Math.max(margin, top) + 'px';
  }

  function closeContextMenu() {
    const menu = $('contextMenu');
    if (!menu) {
      return;
    }
    menu.hidden = true;
    menuActions = [];
  }

  // --- Modale ---

  function closeModalElement(id) {
    if (id === 'previewModal') {
      closePreview();
      return;
    }
    $(id).hidden = true;
  }

  function askText(title, message, value = '', placeholder = '', allowEmpty = false) {
    $('modalTitle').textContent = title;
    $('modalMessage').textContent = message;
    const input = $('modalInput');
    input.hidden = false;
    input.value = value;
    input.placeholder = placeholder;
    modalAllowEmpty = allowEmpty;
    $('modalOk').textContent = 'Zapisz';
    $('modal').hidden = false;
    setTimeout(() => {
      input.focus();
      input.select();
    }, 0);
    return new Promise((resolve) => {
      modalResolver = resolve;
    });
  }

  function confirmAction(title, message, okLabel) {
    $('modalTitle').textContent = title;
    $('modalMessage').textContent = message;
    const input = $('modalInput');
    input.hidden = true;
    input.value = '';
    modalAllowEmpty = false;
    $('modalOk').textContent = okLabel || 'OK';
    $('modal').hidden = false;
    setTimeout(() => $('modalOk').focus(), 0);
    return new Promise((resolve) => {
      modalResolver = resolve;
    });
  }

  function cancelModal() {
    const resolve = modalResolver;
    modalResolver = null;
    $('modal').hidden = true;
    if (resolve) {
      resolve(null);
    }
  }

  function showInfo(entry) {
    $('infoList').innerHTML =
      '<dt>Nazwa</dt><dd>' + escapeHtml(entry.name) + '</dd>'
      + '<dt>Ścieżka</dt><dd>' + escapeHtml(entry.path) + '</dd>'
      + '<dt>Typ</dt><dd>' + (entry.isFolder ? 'Folder' : escapeHtml(extensionOf(entry.name).toUpperCase() || 'plik'))
      + '</dd>'
      + '<dt>Rozmiar</dt><dd>' + (entry.isFolder ? '—' : formatBytes(entry.sizeBytes)) + '</dd>'
      + '<dt>Na serwerze</dt><dd>'
      + (entry.isFolder ? '—' : formatBytes(entry.storedSizeBytes) + ' (zaszyfrowane)') + '</dd>'
      + '<dt>Modyfikacja</dt><dd>' + formatDate(entry.modifiedAt) + '</dd>'
      + '<dt>Gwiazdka</dt><dd>' + (entry.starred ? 'Tak' : 'Nie') + '</dd>'
      + '<dt>Skrót</dt><dd class="mono">' + escapeHtml(entry.pathHash) + '</dd>';
    $('infoModal').hidden = false;
  }

  // --- Zdarzenia ---

  function bindEvents() {
    // Logowanie
    document.querySelectorAll('[data-auth-mode]').forEach((button) => {
      button.addEventListener('click', () => setAuthMode(button.dataset.authMode));
    });
    $('authForm').addEventListener('submit', handleAuthSubmit);

    // Nawigacja
    document.querySelectorAll('.nav-item[data-view]').forEach((button) => {
      button.addEventListener('click', () => setView(button.dataset.view, button.dataset.path || ''));
    });
    document.querySelectorAll('[data-action="root"]').forEach((button) => {
      button.addEventListener('click', () => setView('home', ''));
    });
    document.querySelectorAll('[data-nav="home"]').forEach((button) => {
      button.addEventListener('click', () => setView('home', ''));
    });

    // Widok
    document.querySelectorAll('.view-button[data-view-mode]').forEach((button) => {
      button.addEventListener('click', () => setViewMode(button.dataset.viewMode));
    });

    // Pasek narzędzi
    $('uploadButton').addEventListener('click', () => $('fileInput').click());
    $('newFolderButton').addEventListener('click', createFolder);
    $('emptyTrashButton').addEventListener('click', emptyTrash);
    $('fileInput').addEventListener('change', (event) => {
      const files = Array.from(event.target.files || []);
      event.target.value = '';
      uploadFiles(files, uploadFolder());
    });
    $('folderInput').addEventListener('change', (event) => {
      const files = Array.from(event.target.files || []);
      event.target.value = '';
      handleFolderUpload(files);
    });

    // Filtry
    $('searchInput').addEventListener('input', (event) => {
      state.search = event.target.value;
      render();
    });
    $('typeFilter').addEventListener('change', (event) => {
      state.typeFilter = event.target.value;
      render();
    });
    $('sortSelect').addEventListener('change', (event) => {
      state.sort = event.target.value;
      render();
    });

    // Konto / panele
    $('accountButton').addEventListener('click', (event) => {
      event.stopPropagation();
      $('accountPanel').hidden = !$('accountPanel').hidden;
      $('settingsPanel').hidden = true;
    });
    $('settingsButton').addEventListener('click', (event) => {
      event.stopPropagation();
      $('settingsPanel').hidden = !$('settingsPanel').hidden;
      $('accountPanel').hidden = true;
    });
    $('helpButton').addEventListener('click', () => {
      $('aboutModal').hidden = false;
    });
    $('logoutButton').addEventListener('click', logout);
    $('recoveryButton').addEventListener('click', downloadRecovery);

    // Postęp uploadu
    $('uploadClose').addEventListener('click', () => {
      $('uploadToast').hidden = true;
    });

    // Breadcrumbs
    $('breadcrumbs').addEventListener('click', (event) => {
      const button = event.target.closest('[data-crumb]');
      if (button) {
        setView('home', button.dataset.crumb);
      }
    });

    // Menu kontekstowe
    $('contextMenu').addEventListener('click', (event) => {
      const button = event.target.closest('button[data-index]');
      if (!button) {
        return;
      }
      const action = menuActions[Number(button.dataset.index)];
      closeContextMenu();
      if (action) {
        action.action();
      }
    });

    // Kliknięcia globalne
    document.addEventListener('click', (event) => {
      if (!event.target.closest('#contextMenu') && !event.target.closest('[data-menu]')) {
        closeContextMenu();
      }
      if (!event.target.closest('#accountPanel') && !event.target.closest('#accountButton')) {
        $('accountPanel').hidden = true;
      }
    });

    document.addEventListener('scroll', closeContextMenu, true);
    window.addEventListener('resize', closeContextMenu);

    bindSurfaceEvents();
    bindWorkspaceDrop();
    bindModalEvents();
    bindKeys();
  }

  function bindSurfaceEvents() {
    const surface = $('filesSurface');

    surface.addEventListener('click', (event) => {
      const menuButton = event.target.closest('[data-menu]');
      if (menuButton) {
        const entry = findEntry(menuButton.dataset.menu);
        if (entry) {
          state.selectedHash = entry.pathHash;
          updateSelection();
          openContextMenu(entry, menuButton);
        }
        return;
      }

      const row = event.target.closest('[data-hash]');
      if (!row) {
        state.selectedHash = null;
        updateSelection();
        return;
      }
      state.selectedHash = row.dataset.hash;
      updateSelection();
    });

    surface.addEventListener('dblclick', (event) => {
      const row = event.target.closest('[data-hash]');
      if (!row) {
        return;
      }
      const entry = findEntry(row.dataset.hash);
      if (entry) {
        openEntry(entry);
      }
    });

    surface.addEventListener('contextmenu', (event) => {
      event.preventDefault();
      const row = event.target.closest('[data-hash]');
      if (row) {
        const entry = findEntry(row.dataset.hash);
        state.selectedHash = row.dataset.hash;
        updateSelection();
        openContextMenu(entry, { x: event.clientX, y: event.clientY });
      } else {
        state.selectedHash = null;
        updateSelection();
        openContextMenu(null, { x: event.clientX, y: event.clientY });
      }
    });

    surface.addEventListener('dragstart', (event) => {
      const row = event.target.closest('[data-hash]');
      if (!row) {
        return;
      }
      event.dataTransfer.setData('text/skyvault-hash', row.dataset.hash);
      event.dataTransfer.effectAllowed = 'move';
    });

    surface.addEventListener('dragover', (event) => {
      const row = event.target.closest('[data-hash]');
      if (!row) {
        return;
      }
      const target = findEntry(row.dataset.hash);
      if (target && target.isFolder) {
        event.preventDefault();
        event.dataTransfer.dropEffect = 'move';
        row.classList.add('is-drop-target');
      }
    });

    surface.addEventListener('dragleave', (event) => {
      const row = event.target.closest('[data-hash]');
      if (row) {
        row.classList.remove('is-drop-target');
      }
    });

    surface.addEventListener('drop', async (event) => {
      const row = event.target.closest('[data-hash]');
      if (!row) {
        return;
      }
      row.classList.remove('is-drop-target');
      const hash = event.dataTransfer.getData('text/skyvault-hash');
      if (!hash) {
        return;
      }
      event.preventDefault();
      event.stopPropagation();
      const source = findEntry(hash);
      const target = findEntry(row.dataset.hash);
      if (source && target && target.isFolder) {
        await moveInto(source, target);
      }
    });

    surface.addEventListener('dragend', () => {
      document.querySelectorAll('#filesSurface .is-drop-target').forEach((element) => {
        element.classList.remove('is-drop-target');
      });
    });
  }

  function bindWorkspaceDrop() {
    const workspace = document.querySelector('.workspace');
    let dragDepth = 0;

    workspace.addEventListener('dragenter', (event) => {
      if (!event.dataTransfer || !event.dataTransfer.types.includes('Files')) {
        return;
      }
      dragDepth += 1;
      workspace.classList.add('is-dropping');
    });

    workspace.addEventListener('dragover', (event) => {
      if (event.dataTransfer && event.dataTransfer.types.includes('Files')) {
        event.preventDefault();
        event.dataTransfer.dropEffect = 'copy';
      }
    });

    workspace.addEventListener('dragleave', () => {
      dragDepth = Math.max(0, dragDepth - 1);
      if (!dragDepth) {
        workspace.classList.remove('is-dropping');
      }
    });

    workspace.addEventListener('drop', (event) => {
      dragDepth = 0;
      workspace.classList.remove('is-dropping');
      if (!event.dataTransfer || !event.dataTransfer.files || !event.dataTransfer.files.length) {
        return;
      }
      event.preventDefault();
      uploadFiles(event.dataTransfer.files, uploadFolder());
    });
  }

  function bindModalEvents() {
    $('modalForm').addEventListener('submit', (event) => {
      event.preventDefault();
      const resolve = modalResolver;
      if (!resolve) {
        return;
      }
      const input = $('modalInput');
      const value = input.hidden ? true : input.value.trim();
      if (!input.hidden && !value && !modalAllowEmpty) {
        return;
      }
      modalResolver = null;
      $('modal').hidden = true;
      resolve(value);
    });

    $('modalCancel').addEventListener('click', cancelModal);

    document.querySelectorAll('[data-close-modal]').forEach((button) => {
      button.addEventListener('click', () => {
        const modal = button.closest('.modal');
        if (modal) {
          closeModalElement(modal.id);
        }
      });
    });

    document.querySelectorAll('.modal').forEach((modal) => {
      modal.addEventListener('click', (event) => {
        if (event.target !== modal) {
          return;
        }
        if (modal.id === 'modal') {
          cancelModal();
        } else {
          closeModalElement(modal.id);
        }
      });
    });
  }

  function bindKeys() {
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') {
        closeContextMenu();
        $('accountPanel').hidden = true;
        if (!$('modal').hidden) {
          cancelModal();
        }
        if (!$('previewModal').hidden) {
          closePreview();
        }
        if (!$('infoModal').hidden) {
          $('infoModal').hidden = true;
        }
        if (!$('aboutModal').hidden) {
          $('aboutModal').hidden = true;
        }
        return;
      }

      if (!state.inApp) {
        return;
      }
      if (event.target.matches('input, textarea, select')) {
        return;
      }

      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'f') {
        event.preventDefault();
        $('searchInput').focus();
        return;
      }

      if (!state.selectedHash) {
        return;
      }
      const entry = findEntry(state.selectedHash);
      if (!entry) {
        return;
      }

      if (event.key === 'Enter' && !entry.isTrashed) {
        event.preventDefault();
        openEntry(entry);
      } else if (event.key === 'F2' && !entry.isTrashed) {
        event.preventDefault();
        renameEntry(entry);
      } else if (event.key === 'Delete' && !entry.isTrashed) {
        event.preventDefault();
        trashEntry(entry);
      }
    });
  }

  // --- Start ---

  async function init() {
    state.viewMode = localStorage.getItem('skyvaultViewMode') === 'grid' ? 'grid' : 'list';
    bindEvents();
    setViewMode(state.viewMode);
    setAuthMode('login');

    try {
      await C.ensureSodium();
    } catch (error) {
      showAuthMessage('Nie udało się wczytać biblioteki kryptograficznej. Odśwież stronę.');
      return;
    }

    if (!window.crypto || !window.crypto.subtle) {
      showAuthMessage('Ta przeglądarka nie udostępnia Web Crypto. Otwórz SkyVault przez localhost lub HTTPS.');
      return;
    }

    const profile = await fetchProfile();
    if (profile && C.loadMasterKey()) {
      await enterApp(profile);
      return;
    }

    if (profile) {
      $('authEmail').value = profile.email || C.storedEmail();
      setAuthMode('login');
      showAuthMessage('Sesja jest aktywna, ale klucz główny nie jest wczytany w tej karcie. '
        + 'Podaj hasło, aby odblokować dysk.');
    }
  }

  document.addEventListener('DOMContentLoaded', init);
})();
