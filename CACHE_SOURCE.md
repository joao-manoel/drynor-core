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

O cache empacotado em `../cache/` é o padrão de execução. O checkout
`../cache-unpacked/` é fonte de edição e não deve sobrescrever o cache ativo
somente por existir. Para empacotar uma mudança, pare lobby e world, selecione
explicitamente a fonte e construa em um diretório temporário; confira o pacote
antes de promovê-lo.

| Variável | Padrão |
|---|---|
| CACHE_SOURCE_PATH | não definido; habilita a fonte no core moderno quando definido |
| CACHE_BUILD_PATH | saída separada para o build explícito |
| DRYNOR_CACHE_BUILD | `true` habilita o build no adaptador legado; caso contrário serve o pacote existente |

Falhas interrompem o startup. Um arquivo de lock ao lado da saída serializa
builders, mas não protege leitores ativos. Nunca empacote diretamente em
`../cache/` com lobby ou world em execução. O cache local de produção deve ser
sincronizado do diretório montado pelos containers da VPS, com SHA-256 conferido;
o procedimento está em [WORKSPACE_ORGANIZATION.md](WORKSPACE_ORGANIZATION.md).

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
