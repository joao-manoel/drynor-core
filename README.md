# Drynor Core 3

Core moderno baseado no Drynor `dev` (`b56852003eb02280218757ecfd546d892ca5a25a`),
com os módulos `core`, `cs2` e `tools`, licença GPLv3 e fontes upstream preservadas.
Requer JDK 26. O Gradle wrapper usa 9.7.1.

Clone `drynor-core`, `drynor-world-server`, `drynor-lobby-server` e
`cache-unpacked` lado a lado. O catálogo gameval é lido do cache unpacked;
nenhum cache binário ou segredo é versionado aqui.

Para a disposição atual do workspace, sequência de startup, testes e entrega,
veja [WORKSPACE_RUNBOOK.md](WORKSPACE_RUNBOOK.md). O cache empacotado de
execução é `../cache/`; os comandos abaixo de build/cache são ferramentas de
desenvolvimento, não etapas automáticas de startup.

```powershell
.\gradlew.bat :tools:installDist :cs2:test :legacy-compat:build
.\build-cache.bat
.\gradlew.bat :legacy-compat:verifyPackedCache
```

O core principal é o moderno (`modern-core/`, pacote `org.drynor`).
Para preservar todo o gameplay Drynor, as APIs `com.rs` ainda consumidas pelos
servidores foram isoladas em `legacy-compat`. O módulo depende do core moderno
e CS2; o build do cache com `Builder` é explícito. A migração completa
de entidades, transporte e persistência dos consumidores segue separada da
importação dos módulos; não foi substituído gameplay por conteúdo upstream.

Leia [CACHE_SOURCE.md](CACHE_SOURCE.md) para configuração e comandos CS2.
As ferramentas mantêm recursos upstream como compilação, decompilação, VM,
análise e editores. [upstream-docs](upstream-docs/) contém os contratos originais.

Para atualizações upstream, compare pelo commit registrado, preserve mudanças
Drynor em EnvVars, builds e adapters, e rode os gates de cache/CS2 e dos servidores.
O projeto de referência `../Referencia/drynor-cache-tools` não é necessário para executar
esta integração: core, CS2 e tools estão neste repositório.
