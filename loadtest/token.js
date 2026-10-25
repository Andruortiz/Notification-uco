import { generateKeyPairSync, createSign } from 'node:crypto';

const base64url = (buffer) => Buffer.from(buffer).toString('base64url');

const tenant = process.env.LOADTEST_TENANT || 'loadtest-tenant';
const ttlSegundos = Number(process.env.LOADTEST_TOKEN_TTL_SECONDS || 3600);
const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const ahora = Math.floor(Date.now() / 1000);
const cabecera = base64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
const carga = base64url(
  JSON.stringify({
    sub: 'loadtest',
    tenantId: tenant,
    role: 'ADMINISTRADOR',
    iat: ahora,
    exp: ahora + ttlSegundos,
  }),
);
const firmante = createSign('RSA-SHA256');
firmante.update(`${cabecera}.${carga}`);
const firma = base64url(firmante.sign(privateKey));

process.stdout.write(
  JSON.stringify({
    publicKey: publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
    bearer: `${cabecera}.${carga}.${firma}`,
  }),
);
