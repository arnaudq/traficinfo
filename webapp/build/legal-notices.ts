import type { Plugin } from 'vite';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const repositoryDirectory = fileURLToPath(new URL('../../', import.meta.url));
const repositoryUrl = 'https://github.com/arnaudq/traficinfo';

/** Publish the same licence texts in development and in the built website. */
export function legalNotices(): Plugin {
  const revision = execFileSync('git', ['rev-parse', 'HEAD'], {
    cwd: repositoryDirectory,
    encoding: 'utf8',
  }).trim();

  const leafletPackage = JSON.parse(
    readFileSync(
      new URL('../node_modules/leaflet/package.json', import.meta.url),
      'utf8',
    ),
  );

  const licences = [
    {
      path: 'licenses/AGPL-3.0.txt',
      contents: readFileSync(new URL('../../LICENSE', import.meta.url), 'utf8'),
    },
    {
      path: 'licenses/Leaflet.txt',
      contents: readFileSync(
        new URL('../node_modules/leaflet/LICENSE', import.meta.url),
        'utf8',
      ),
    },
  ];

  return {
    name: 'traficinfo-legal-notices',
    transformIndexHtml(html) {
      return html
        .replaceAll('__SOURCE_URL__', `${repositoryUrl}/tree/${revision}`)
        .replaceAll(
          '__SOURCE_ARCHIVE_URL__',
          `${repositoryUrl}/archive/${revision}.zip`,
        )
        .replaceAll('__LEAFLET_VERSION__', leafletPackage.version);
    },
    generateBundle() {
      for (const licence of licences) {
        this.emitFile({
          type: 'asset',
          fileName: licence.path,
          source: licence.contents,
        });
      }
    },
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        const pathname = new URL(request.url ?? '/', 'http://localhost')
          .pathname;
        const licence = licences.find((item) => pathname === `/${item.path}`);
        if (!licence) {
          next();
          return;
        }

        response.setHeader('Content-Type', 'text/plain; charset=utf-8');
        response.end(licence.contents);
      });
    },
  };
}
