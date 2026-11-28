// Dashboard: daily chart from /api/v1/chart-data, refreshed live from STOMP events on /topic/metrics.
// Events carry no figures: on STATS_UPDATED the chart is fetched again, on JOB_FINISHED the run status is.
(function () {
  'use strict';

  const COLORS = { FACEBOOK: '#1877f2', TWITTER: '#0f1419' };
  const csrfToken = document.querySelector('meta[name="_csrf"]').content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;
  const metricSelect = document.getElementById('metric');
  const daysSelect = document.getElementById('days');
  const live = document.getElementById('live');

  let chart = null;
  let lastData = null;

  function setLive(state, text) {
    live.dataset.state = state;
    live.textContent = text;
  }

  function isoDate(date) {
    return date.toLocaleDateString('en-CA', { timeZone: document.getElementById('charts').dataset.timeZone });
  }

  async function loadChart() {
    const to = new Date();
    const from = new Date(to.getTime() - (Number(daysSelect.value) - 1) * 24 * 3600 * 1000);
    const response = await fetch(`/api/v1/chart-data?from=${isoDate(from)}&to=${isoDate(to)}`,
        { credentials: 'same-origin', headers: { Accept: 'application/json' } });
    if (response.status === 401) {
      window.location.assign('/login');
      return;
    }
    if (!response.ok) {
      throw new Error(`chart-data: ${response.status}`);
    }
    lastData = await response.json();
    draw();
  }

  function draw() {
    if (!lastData) {
      return;
    }
    const metric = metricSelect.value;
    const datasets = lastData.series.map((series) => ({
      label: series.platform === 'TWITTER' ? 'X' : 'Facebook',
      data: series[metric],
      borderColor: COLORS[series.platform],
      backgroundColor: COLORS[series.platform],
      tension: 0.2,
      // Days with nothing measured (values carried from the day before) are drawn hollow.
      pointStyle: series.postsMeasured.map((measured) => (measured > 0 ? 'circle' : 'crossRot')),
      pointRadius: 3,
    }));
    if (chart) {
      chart.data.labels = lastData.dates;
      chart.data.datasets = datasets;
      chart.update();
      return;
    }
    chart = new Chart(document.getElementById('chart'), {
      type: 'line',
      data: { labels: lastData.dates, datasets },
      options: {
        animation: false,
        interaction: { mode: 'index', intersect: false },
        scales: { y: { beginAtZero: true } },
      },
    });
  }

  async function refreshRunStatus() {
    const response = await fetch('/dashboard/run-status', { credentials: 'same-origin' });
    if (response.ok) {
      document.getElementById('run-status').outerHTML = await response.text();
    }
  }

  function connect() {
    const client = new StompJs.Client({
      webSocketFactory: () => new SockJS('/ws'),
      connectHeaders: { [csrfHeader]: csrfToken },
      // Reconnects on its own after a drop (server restart, network); the chart is reloaded on reconnect.
      reconnectDelay: 5000,
      heartbeatIncoming: 20000,
      heartbeatOutgoing: 20000,
    });
    client.onConnect = () => {
      setLive('connected', 'Đang cập nhật trực tiếp');
      client.subscribe('/topic/metrics', (message) => {
        const event = JSON.parse(message.body);
        if (event.type === 'STATS_UPDATED') {
          loadChart().catch(console.error);
        } else if (event.type === 'JOB_FINISHED') {
          refreshRunStatus().catch(console.error);
        }
      });
      // Anything missed while disconnected.
      loadChart().catch(console.error);
    };
    client.onWebSocketClose = () => setLive('disconnected', 'Mất kết nối, đang thử lại…');
    client.onStompError = (frame) => {
      console.error('STOMP error', frame.headers.message);
      setLive('disconnected', 'Không kết nối được, đang thử lại…');
    };
    client.activate();
    window.dashboardStomp = client;
  }

  metricSelect.addEventListener('change', draw);
  daysSelect.addEventListener('change', () => loadChart().catch(console.error));
  loadChart().catch(console.error);
  connect();
})();
