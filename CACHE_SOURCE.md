# Core moderno, cache source e CS2

Base: Drynor server dev, commit b56852003eb02280218757ecfd546d892ca5a25a.
Fontes e licença GPLv3 preservadas. Versão Drynor: 3.0.0-drynor-SNAPSHOT.

| Módulo | Responsabilidade |
|---|---|
| core (modern-core/) | Core atual do Drynor: rede, cache, modelos, Mongo, social, codecs |
| cs2 | Compiler TypeScript, decompiler, VM e codec source do índice 12 |
| tools | CLI e ferramentas visuais upstream |
| legacy-compat | Contratos com.rs usados pelo gameplay Drynor durante a migração |

O core principal usa org.drynor; o gameplay existente ainda usa o módulo
separado de compatibilidade. Não houve substituição do conteúdo do world
pelo gameplay upstream. Migrar cada consumidor às APIs modernas é trabalho
incremental; importar o core não transforma o gameplay inteiro automaticamente.

JDK 26 e Gradle 9.7.1 são necessários. Lobby/world usam composite build para
legacy-compat, que depende do core moderno e do CS2. O cache é construído
diretamente pela implementação moderna no mesmo processo.

Se ../cache-unpacked/cache.json existir, Cache.init(path) constrói esse tree
no caminho packed solicitado. Configuração opcional:

| Variável | Padrão |
|---|---|
| CACHE_SOURCE_PATH | ../cache-unpacked |
| CACHE_BUILD_PATH | argumento de Cache.init |
| DRYNOR_CACHE_BUILD | false desativa o build automático |

Falhas interrompem o startup. Um arquivo de lock ao lado da saída serializa
builders de lobby/world. Pare servidores antes de reconstruir: o lock não
protege leitores que já estão ativos.

```powershell
.\build-cache.bat
.\drynor-tools.ps1 cache status ../cache-unpacked
.\drynor-tools.ps1 cache verify ../cache-unpacked ../cache
.\drynor-tools.ps1 cs2 info 3940
.\drynor-tools.ps1 cs2 decompile 3940
.\gradlew.bat :legacy-compat:verifyPackedCache
```

Edite ../cache-unpacked/clientscripts e reconstrua para compilar o índice 12.
Documentação completa do cache e CS2 em upstream-docs/.
O core 2.0.5 publicado não é sobrescrito. Nenhuma chave pertence a este repo.