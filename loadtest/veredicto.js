export const UMBRALES = {
  p95Seconds: 0.2,
  ratio202: 0.99,
  despachoPorMinuto: 500,
};

export const METRICAS = {
  aceptacion: 'notificaciones_aceptacion_seconds',
  despacho: 'notificaciones_despachadas_por_minuto',
  valida: 'notificaciones_ejecucion_valida',
};

export function evaluar(mediciones, umbrales = UMBRALES) {
  const fallos = [];
  if (mediciones.contadorReiniciado) {
    fallos.push('ejecucion invalida: el contador de despacho se reinicio durante la ventana');
  }
  if (!(mediciones.p95Seconds <= umbrales.p95Seconds)) {
    fallos.push(`RNF-02: p95 ${mediciones.p95Seconds} s supera ${umbrales.p95Seconds} s`);
  }
  if (!(mediciones.ratio202 >= umbrales.ratio202)) {
    fallos.push(`202 ${mediciones.ratio202} por debajo de ${umbrales.ratio202}`);
  }
  if (!mediciones.contadorReiniciado && !(mediciones.despachoPorMinuto >= umbrales.despachoPorMinuto)) {
    fallos.push(
      `RNF-03: despacho ${mediciones.despachoPorMinuto}/min por debajo de ${umbrales.despachoPorMinuto}/min`,
    );
  }
  return { ok: fallos.length === 0, fallos };
}

export function despachoPorMinuto(incremento, minutos) {
  return incremento / minutos;
}

export function validarEntorno(env) {
  const errores = [];
  const base = env.LOADTEST_BASE_URL || 'http://localhost:8060';
  let host;
  try {
    host = new URL(base).hostname;
  } catch {
    return ['LOADTEST_BASE_URL invalida'];
  }
  if (env.LOADTEST_CONFIRM_SIMULATED !== 'true') {
    errores.push('LOADTEST_CONFIRM_SIMULATED=true es obligatorio');
  }
  if (host !== 'localhost' && env.LOADTEST_ALLOW_REMOTE !== 'true') {
    errores.push(`host ${host} exige LOADTEST_ALLOW_REMOTE=true`);
  }
  if (!env.LOADTEST_BEARER && !env.LOADTEST_COOKIE) {
    errores.push('LOADTEST_BEARER o LOADTEST_COOKIE es obligatorio');
  }
  return errores;
}

const PATRON_SECRETO = /eyJ[\w-]+\.[\w-]+\.[\w-]+|bearer\s+\S+|BEGIN [A-Z ]*PRIVATE KEY/i;

export function contieneSecretos(texto) {
  return PATRON_SECRETO.test(texto);
}

export function construirResultado({ fecha, commit, parametros, mediciones, umbrales = UMBRALES }) {
  const { ok, fallos } = evaluar(mediciones, umbrales);
  const resultado = {
    fecha,
    commit,
    parametros: {
      tasaPorMinuto: parametros.tasaPorMinuto,
      ventanaMinutos: parametros.ventanaMinutos,
      calentamientoMinutos: parametros.calentamientoMinutos,
    },
    mediciones: {
      p95Seconds: mediciones.p95Seconds,
      ratio202: mediciones.ratio202,
      despachoPorMinuto: mediciones.despachoPorMinuto,
      contadorReiniciado: Boolean(mediciones.contadorReiniciado),
    },
    umbrales,
    veredicto: ok ? 'CUMPLE' : 'NO_CUMPLE',
    fallos,
  };
  const texto = JSON.stringify(resultado, null, 2);
  if (contieneSecretos(texto)) {
    throw new Error('resultado con material secreto: se descarta');
  }
  return texto;
}
