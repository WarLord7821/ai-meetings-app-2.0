/**
 * Default environment settings.
 * Individual deployment environments (dev/prod/test) import and override
 * values in their own `environment.<stage>.ts` files.
 */
export const environment = {
  /** Whether this build targets a production deployment. */
  production: false,

  /** Base URL of the Spring Boot REST API. */
  apiBaseUrl: 'http://localhost:8080',
};