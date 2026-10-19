# Quickstart: verificar el contrato v2

Con Docker en marcha y `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"` definido.

1. Guardias de contrato (sin Docker):
   `./mvnw -B -ntp -pl infrastructure -am test -Dtest='ApiContractLiteralPathsTest,RouteAuthorizationPolicyContractTest,ProtectedResourcesCatalogTest,SecurityDocumentationContractTest,ChangelogContractTest,ErrorResponseContractTest' -Dsurefire.failIfNoSpecifiedTests=false`
2. Contrato frente a controladores y conformidad de respuestas (con Docker):
   `./mvnw -B -ntp -pl infrastructure -am test -Dtest='ApiContractMatchesControllersTest,ResponseSchemasConformanceE2ETest,ApiV2RoutesE2ETest' -Dsurefire.failIfNoSpecifiedTests=false`
3. Lista canónica: tras el paso 1 existe `infrastructure/target/contract/protected-resources.json` con 17 entradas.
4. Arquitectura: `HexagonalArchitectureTest` y `ModularityTests`.
5. Puerta completa: `./mvnw -B -ntp verify` (en segundo plano, unos 10 minutos).
6. Prueba manual opcional con el servicio levantado (puerto 8060): `GET /notifications/detail?notificationId=<uuid>` responde
   como antes el `GET /notifications/<uuid>`; `GET /notifications/<uuid>` responde 404.
