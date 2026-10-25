import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Trend, Gauge, Rate } from 'k6/metrics';
import {
  UMBRALES,
  METRICAS,
  validarEntorno,
  despachoPorMinuto,
  construirResultado,
} from './veredicto.js';

const TASA_POR_MINUTO = Number(__ENV.LOADTEST_RATE_PER_MINUTE || 700);
const VENTANA_MINUTOS = Number(__ENV.LOADTEST_WINDOW_MINUTES || 5);
const CALENTAMIENTO_MINUTOS = Number(__ENV.LOADTEST_WARMUP_MINUTES || 1);
const DRENAJE_SEGUNDOS = Number(__ENV.LOADTEST_DRAIN_SECONDS || 45);
const BASE_URL = __ENV.LOADTEST_BASE_URL || 'http://localhost:8060';
const PROMETHEUS_URL = __ENV.PROMETHEUS_URL || 'http://localhost:9090';
const EJECUCION = __ENV.LOADTEST_RUN_ID || String(Date.now());
const SERIE =
  'notification_dispatched_total{result="delivered",provider="simulated"}';

const aceptacion = new Trend(METRICAS.aceptacion, true);
const ratio202 = new Rate('notificaciones_ratio_202');
const despacho = new Gauge(METRICAS.despacho);
const valida = new Rate(METRICAS.valida);

export const options = {
  scenarios: {
    calentamiento: {
      executor: 'constant-arrival-rate',
      rate: TASA_POR_MINUTO,
      timeUnit: '1m',
      duration: `${CALENTAMIENTO_MINUTOS}m`,
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
    medida: {
      executor: 'constant-arrival-rate',
      rate: TASA_POR_MINUTO,
      timeUnit: '1m',
      duration: `${VENTANA_MINUTOS}m`,
      startTime: `${CALENTAMIENTO_MINUTOS}m`,
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  summaryTrendStats: ['avg', 'p(95)', 'max'],
  thresholds: {
    [METRICAS.aceptacion]: [`p(95)<=${UMBRALES.p95Seconds}`],
    notificaciones_ratio_202: [`rate>=${UMBRALES.ratio202}`],
    [METRICAS.despacho]: [`value>=${UMBRALES.despachoPorMinuto}`],
    [METRICAS.valida]: ['rate==1'],
  },
};

function cabeceras() {
  const base = { 'Content-Type': 'application/json' };
  if (__ENV.LOADTEST_BEARER) {
    base.Authorization = `Bearer ${__ENV.LOADTEST_BEARER}`;
  } else {
    base.Cookie = __ENV.LOADTEST_COOKIE;
  }
  return base;
}

function consultar(expresion, instante) {
  const url = `${PROMETHEUS_URL}/api/v1/query?query=${encodeURIComponent(expresion)}&time=${instante}`;
  const respuesta = http.get(url, { tags: { name: 'prometheus' } });
  if (respuesta.status !== 200) {
    throw new Error(`Prometheus respondio ${respuesta.status}`);
  }
  const resultado = respuesta.json('data.result');
  return resultado.length === 0 ? 0 : Number(resultado[0].value[1]);
}

export function setup() {
  const errores = validarEntorno(__ENV);
  if (errores.length > 0) {
    exec.test.abort(`entorno rechazado: ${errores.join('; ')}`);
  }
  const listo = http.get(`${PROMETHEUS_URL}/-/ready`, { tags: { name: 'prometheus' } });
  if (listo.status !== 200) {
    exec.test.abort(`Prometheus no disponible en ${PROMETHEUS_URL}`);
  }
  return { inicioMedida: Date.now() + CALENTAMIENTO_MINUTOS * 60000 };
}

export default function () {
  const cuerpo = JSON.stringify({
    externalId: `run-${EJECUCION}-${exec.scenario.iterationInTest}`,
    channelType: 'EMAIL',
    recipientId: 'loadtest-recipient',
    recipientAddress: 'loadtest@example.invalid',
    subject: 'Prueba de carga',
    body: 'Prueba de carga',
    priority: 'NORMAL',
  });
  const respuesta = http.post(`${BASE_URL}/notifications`, cuerpo, {
    headers: cabeceras(),
    tags: { name: 'POST /notifications' },
  });
  if (exec.scenario.name === 'medida') {
    aceptacion.add(respuesta.timings.duration / 1000);
    ratio202.add(respuesta.status === 202);
  }
}

export function teardown(datos) {
  sleep(DRENAJE_SEGUNDOS);
  const ahora = Math.floor(Date.now() / 1000);
  const segundos = Math.max(1, Math.ceil((Date.now() - datos.inicioMedida) / 1000));
  const reinicios = consultar(`sum(resets(${SERIE}[${segundos}s]))`, ahora);
  const incremento = consultar(`sum(increase(${SERIE}[${segundos}s]))`, ahora);
  valida.add(reinicios === 0);
  despacho.add(despachoPorMinuto(incremento, VENTANA_MINUTOS));
}

export function handleSummary(datos) {
  const valor = (nombre, campo) => {
    const metrica = datos.metrics[nombre];
    return metrica ? metrica.values[campo] : NaN;
  };
  const texto = construirResultado({
    fecha: new Date().toISOString(),
    commit: __ENV.LOADTEST_COMMIT || 'desconocido',
    parametros: {
      tasaPorMinuto: TASA_POR_MINUTO,
      ventanaMinutos: VENTANA_MINUTOS,
      calentamientoMinutos: CALENTAMIENTO_MINUTOS,
    },
    mediciones: {
      p95Seconds: valor(METRICAS.aceptacion, 'p(95)'),
      ratio202: valor('notificaciones_ratio_202', 'rate'),
      despachoPorMinuto: valor(METRICAS.despacho, 'value'),
      contadorReiniciado: valor(METRICAS.valida, 'rate') !== 1,
    },
  });
  return {
    [__ENV.LOADTEST_RESULT_FILE || 'resultado.json']: texto,
    stdout: `${JSON.parse(texto).veredicto}\n`,
  };
}
