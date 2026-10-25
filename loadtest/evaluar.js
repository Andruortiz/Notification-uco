import { evaluar, UMBRALES } from './veredicto.js';

let entrada = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (trozo) => {
  entrada += trozo;
});
process.stdin.on('end', () => {
  const { mediciones, umbrales } = JSON.parse(entrada);
  const resultado = evaluar(mediciones, { ...UMBRALES, ...umbrales });
  process.stdout.write(JSON.stringify(resultado));
  process.exit(resultado.ok ? 0 : 1);
});
