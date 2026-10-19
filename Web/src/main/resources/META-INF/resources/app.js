'use strict';

/**
 * Panel kontrolny SkyVault.
 *
 * Komunikuje się z API serwera:
 *   GET /api/status  - licznik współbieżności i informacje o serwerze
 *   GET /api/ping    - pojedyncze żądanie, opcjonalnie z opóźnieniem (delayMs)
 */

const POLL_INTERVAL_MS = 3000;
const BURST_REQUESTS = 40;

const el = {
    dot: document.getElementById('status-dot'),
    active: document.getElementById('active-value'),
    max: document.getElementById('max-value'),
    available: document.getElementById('available-value'),
    peak: document.getElementById('peak-value'),
    rejected: document.getElementById('rejected-value'),
    bar: document.getElementById('bar-fill'),
    app: document.getElementById('app-value'),
    version: document.getElementById('version-value'),
    java: document.getElementById('java-value'),
    uptime: document.getElementById('uptime-value'),
    footer: document.getElementById('footer-info'),
    log: document.getElementById('log'),
    delay: document.getElementById('delay-select'),
    btnRefresh: document.getElementById('btn-refresh'),
    btnPing: document.getElementById('btn-ping'),
    btnBurst: document.getElementById('btn-burst'),
};

/* --- Log --- */

function log(message, kind) {
    const line = document.createElement('div');
    line.className = 'log-line' + (kind ? ' ' + kind : '');

    const time = document.createElement('span');
    time.className = 'time';
    time.textContent = new Date().toLocaleTimeString('pl-PL');

    const text = document.createElement('span');
    text.className = 'text';
    text.textContent = message;

    line.append(time, text);
    el.log.append(line);
    el.log.scrollTop = el.log.scrollHeight;
}

/* --- Pomocnicze --- */

function setConnection(online) {
    el.dot.classList.toggle('online', online);
    el.dot.classList.toggle('offline', !online);
}

function formatUptime(seconds) {
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = seconds % 60;
    if (h > 0) return `${h} h ${m} min`;
    if (m > 0) return `${m} min ${s} s`;
    return `${s} s`;
}

function formatNumber(value) {
    return new Intl.NumberFormat('pl-PL').format(value);
}

async function getJson(url) {
    const response = await fetch(url, { cache: 'no-store' });
    const body = await response.json().catch(() => null);
    return { status: response.status, ok: response.ok, body };
}

/* --- Odświeżanie stanu --- */

async function refreshStatus(quiet) {
    try {
        const { status, ok, body } = await getJson('/api/status');

        if (!ok) {
            setConnection(false);
            if (!quiet) log(`Błąd pobierania stanu: HTTP ${status}`, 'bad');
            return;
        }

        setConnection(true);
        renderStatus(body);
        if (!quiet) log('Stan serwera odświeżony.', 'ok');
    } catch (error) {
        setConnection(false);
        if (!quiet) log('Serwer nie odpowiada: ' + error.message, 'bad');
    }
}

function renderStatus(s) {
    el.active.textContent = formatNumber(s.activeUsers);
    el.max.textContent = formatNumber(s.maxConcurrentUsers);
    el.available.textContent = formatNumber(s.availableSlots);
    el.peak.textContent = formatNumber(s.peakActiveUsers);
    el.rejected.textContent = formatNumber(s.rejectedTotal);
    el.app.textContent = s.application;
    el.version.textContent = s.version;
    el.java.textContent = s.javaVersion;
    el.uptime.textContent = formatUptime(s.uptimeSeconds);

    const percent = s.maxConcurrentUsers === 0
        ? 0
        : Math.min(100, (s.activeUsers / s.maxConcurrentUsers) * 100);

    el.bar.style.width = percent.toFixed(1) + '%';
    el.bar.classList.toggle('warn', percent >= 70 && percent < 90);
    el.bar.classList.toggle('bad', percent >= 90);

    el.footer.textContent =
        `Limit: ${s.maxConcurrentUsers} użytkowników · oczekiwanie na wolne miejsce: `
        + `${s.acquireTimeoutMs} ms · start: ${new Date(s.startedAt).toLocaleString('pl-PL')}`;
}

/* --- Akcje --- */

async function sendPing() {
    const delayMs = Number(el.delay.value);
    const started = performance.now();

    try {
        const { status, ok, body } = await getJson(`/api/ping?delayMs=${delayMs}`);
        const elapsed = Math.round(performance.now() - started);

        if (ok) {
            log(`Ping OK (${elapsed} ms, opóźnienie ${delayMs} ms, serwer: ${body.timestamp})`, 'ok');
        } else if (status === 429) {
            log(`Ping odrzucony: HTTP 429 — limit ${body.maxConcurrentUsers} użytkowników zajęty.`, 'warn');
        } else {
            log(`Ping nieudany: HTTP ${status}`, 'bad');
        }
    } catch (error) {
        log('Ping nieudany: ' + error.message, 'bad');
    }

    await refreshStatus(true);
}

async function runBurst() {
    const delayMs = Number(el.delay.value);
    el.btnBurst.disabled = true;
    log(`Test obciążenia: ${BURST_REQUESTS} równoległych żądań, opóźnienie ${delayMs} ms…`);

    const started = performance.now();

    const results = await Promise.all(
        Array.from({ length: BURST_REQUESTS }, () =>
            getJson(`/api/ping?delayMs=${delayMs}`)
                .then((r) => r.status)
                .catch(() => 0)
        )
    );

    const elapsed = Math.round(performance.now() - started);
    const ok = results.filter((s) => s === 200).length;
    const limited = results.filter((s) => s === 429).length;
    const failed = results.filter((s) => s === 0).length;

    log(
        `Wynik: ${ok} × 200 OK, ${limited} × 429 Too Many Requests, ${failed} × błąd sieci — razem ${elapsed} ms.`,
        limited > 0 ? 'warn' : 'ok'
    );
    log(
        'Przeglądarka otwiera do jednego serwera tylko kilka połączeń naraz, dlatego liczba '
        + 'jednoczesnych żądań jest tu ograniczona technicznie, a nie przez serwer.',
    );

    el.btnBurst.disabled = false;
    await refreshStatus(true);
}

/* --- Start --- */

el.btnRefresh.addEventListener('click', () => refreshStatus(false));
el.btnPing.addEventListener('click', sendPing);
el.btnBurst.addEventListener('click', runBurst);

log('Panel kontrolny gotowy. Odpytywanie /api/status co 3 s.');
refreshStatus(true);
setInterval(() => refreshStatus(true), POLL_INTERVAL_MS);
