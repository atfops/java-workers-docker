# Dépôt du PFE WebCube 2023/2024

## Overview

- `LSP/`: Contains Docker container that set up **LSP** server (for autocompletion, linter ...)
  
- `api/`: Houses for **api** code & Dockerfile to build a container.

- `java-workers/`: Comprises **java compiler** which is ready to launch as Docker container.
  
- `mariadb/`: Holds docker-compose to deploy **mariadb database** server.
  
- `rabbitmq/`: Contains docker-compose to deploy **rabbitmq** server.
  
- `web-app/`: Houses for **web-app** source code.


## Developing

Once you've created a project and installed dependencies with `npm install` (or `pnpm install` or `yarn`), start a development server:

```bash
npm run dev

# or start the server and open the app in a new browser tab
npm run dev -- --open
```

## Building

To create a production version of your app:

```bash
npm run build
```

You can preview the production build with `npm run preview`.

## Versions
 
**Dernière version stable :** 1.0  
**Dernière version :** 1.0  
Liste des versions : [Cliquer pour afficher](https://github.com/IDE-PFE-S9/WebCube/releases)

## Auteurs

* **Erwan G.** _alias_ [@Wawone](https://github.com/Wawone)

* **Arthur M.** _alias_ [@ArthurMynl](https://github.com/ArthurMynl)

* **Ronan M.** _alias_ [@Warfird](https://github.com/Warfird)

* **Théo L.** _alias_ [@theolurat](https://github.com/theolurat)

