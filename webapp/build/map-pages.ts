import type { Plugin } from 'vite';
import { readFile } from 'node:fs/promises';
import { mapRoutes } from '../src/map-navigation.ts';
import { readRegistry } from './map-config.ts';

export function mapPages(): Plugin {
  return {
    name: 'traficinfo-map-pages',
    generateBundle: {
      order: 'post',
      async handler(_options, bundle) {
        const index = bundle['index.html'];
        if (!index || index.type !== 'asset')
          throw new Error('Missing index.html');
        const html = String(index.source).replace(
          '<base href="./"',
          '<base href="../"',
        );
        for (const path of mapRoutes((await readRegistry()).maps).keys()) {
          this.emitFile({
            type: 'asset',
            fileName: `${path}/index.html`,
            source: html,
          });
        }
      },
    },
    configureServer(server) {
      server.middlewares.use(async (request, response, next) => {
        try {
          const url = new URL(request.url ?? '/', 'http://localhost');
          const path = url.pathname.slice(1).replace(/\/$/, '');
          if (!/^[A-Za-z0-9][A-Za-z0-9_-]*$/.test(path)) return next();
          if (!mapRoutes((await readRegistry()).maps).has(path)) return next();
          if (!url.pathname.endsWith('/')) {
            response.writeHead(302, {
              Location: `${url.pathname}/${url.search}`,
            });
            response.end();
            return;
          }
          const html = (
            await readFile(new URL('../index.html', import.meta.url), 'utf8')
          ).replace('<base href="./"', '<base href="../"');
          response.setHeader('Content-Type', 'text/html; charset=utf-8');
          response.end(await server.transformIndexHtml(url.pathname, html));
        } catch (error) {
          next(error);
        }
      });
    },
  };
}
