import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  UMBRALES,
  evaluar,
  despachoPorMinuto,
  validarEntorno,
  contieneSecretos,
  construirResultado,
} from './veredicto.js';

const bueno = { p95Seconds: 0.05, ratio202: 1, despachoPorMinuto: 690, contadorReiniciado: false };
const parametros = { tasaPorMinuto: 700, ventanaMinutos: 5, calentamientoMinutos: 1 };

test('cumple cuando todo esta dentro de los umbrales', () => {
  assert.deepEqual(evaluar(bueno), { ok: true, fallos: [] });
});

test('el limite exacto cumple', () => {
  const limite = { p95Seconds: 0.2, ratio202: 0.99, despachoPorMinuto: 500, contadorReiniciado: false };
  assert.equal(evaluar(limite).ok, true);
});

test('cada umbral incumplido se nombra', () => {
  assert.match(evaluar({ ...bueno, p95Seconds: 0.21 }).fallos[0], /RNF-02/);
  assert.match(evaluar({ ...bueno, ratio202: 0.98 }).fallos[0], /202/);
  assert.match(evaluar({ ...bueno, despachoPorMinuto: 499 }).fallos[0], /RNF-03/);
});

test('un valor ausente o NaN falla', () => {
  assert.equal(evaluar({ ...bueno, p95Seconds: NaN }).ok, false);
  assert.equal(evaluar({ ...bueno, despachoPorMinuto: undefined }).ok, false);
});

test('un reinicio del contador invalida la ejecucion sin extrapolar', () => {
  const resultado = evaluar({ ...bueno, contadorReiniciado: true });
  assert.equal(resultado.ok, false);
  assert.equal(resultado.fallos.length, 1);
  assert.match(resultado.fallos[0], /invalida/);
});

test('control negativo: un umbral inalcanzable falla', () => {
  assert.equal(evaluar(bueno, { ...UMBRALES, despachoPorMinuto: 1e9 }).ok, false);
});

test('despacho por minuto', () => {
  assert.equal(despachoPorMinuto(3500, 5), 700);
});

test('la guarda exige confirmacion, host local y credencial', () => {
  const ok = { LOADTEST_CONFIRM_SIMULATED: 'true', LOADTEST_BEARER: 'x' };
  assert.deepEqual(validarEntorno(ok), []);
  assert.equal(validarEntorno({ LOADTEST_BEARER: 'x' }).length, 1);
  assert.equal(validarEntorno({ LOADTEST_CONFIRM_SIMULATED: 'true' }).length, 1);
  const remoto = { ...ok, LOADTEST_BASE_URL: 'https://api.ejemplo.com' };
  assert.match(validarEntorno(remoto)[0], /LOADTEST_ALLOW_REMOTE/);
  assert.deepEqual(validarEntorno({ ...remoto, LOADTEST_ALLOW_REMOTE: 'true' }), []);
  assert.deepEqual(validarEntorno({ ...ok, LOADTEST_BASE_URL: 'http://localhost:9000/x' }), []);
});

test('SC-004: el resultado no contiene secretos aunque los reciba', () => {
  const jwt = 'eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.firma_secreta';
  const texto = construirResultado({
    fecha: '2026-10-24T06:00:00Z',
    commit: 'abc123',
    parametros: { ...parametros, authorization: `Bearer ${jwt}`, bearer: jwt },
    mediciones: { ...bueno, cabecera: `Bearer ${jwt}` },
  });
  assert.equal(texto.includes(jwt), false);
  assert.equal(texto.includes('Bearer'), false);
  assert.equal(JSON.parse(texto).veredicto, 'CUMPLE');
});

test('control positivo: el detector reconoce un JWT, un Bearer y una clave privada', () => {
  assert.equal(contieneSecretos('{"t":"eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.abc"}'), true);
  assert.equal(contieneSecretos('Authorization: Bearer abc.def'), true);
  assert.equal(contieneSecretos('-----BEGIN PRIVATE KEY-----'), true);
  assert.equal(contieneSecretos('{"veredicto":"CUMPLE"}'), false);
});

test('un commit con forma de secreto se descarta', () => {
  assert.throws(() =>
    construirResultado({ fecha: 'x', commit: 'Bearer abc', parametros, mediciones: bueno }),
  );
});

test('veredicto NO_CUMPLE lista los fallos', () => {
  const r = JSON.parse(
    construirResultado({
      fecha: 'x',
      commit: 'c',
      parametros,
      mediciones: { ...bueno, p95Seconds: 1 },
    }),
  );
  assert.equal(r.veredicto, 'NO_CUMPLE');
  assert.equal(r.fallos.length, 1);
});
