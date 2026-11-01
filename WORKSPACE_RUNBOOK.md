# Drynor: execução e entrega

Este é o ponto de entrada operacional versionado do workspace. Cada diretório
`drynor-*` é um repositório Git independente; `cache/`, `cache-unpacked/`,
`Referencia/` e `site/` têm papéis diferentes. Leia
[WORKSPACE_ORGANIZATION.md](WORKSPACE_ORGANIZATION.md)
antes de copiar cache ou limpar arquivos. Roadmaps e análises datados registram
o contexto da época, não substituem o estado do código e da implantação atual.

## Ambiente local

| Componente | Checkout | Runtime | Dados e porta |
|---|---|---|---|
| Core, CS2 e ferramentas | `drynor-core/` | JDK 26 | Composite build dos servidores |
| MongoDB | `drynor-world-server/docker-compose.local.yml` | Docker | `127.0.0.1:27017`, volume persistente |
| Lobby | `drynor-lobby-server/` | JDK 26 | TCP 43594; HTTP 4040; banco local próprio |
| World | `drynor-world-server/` | JDK 26 | Jogo 43595; API 43596; banco local próprio |
| Cliente | `drynor-client-v2/` | JDK 19 | Conecta em `127.0.0.1:43594` no teste local |

O cache empacotado de execução é somente `A:/dev/drynor/cache/`, sincronizado
e verificado contra o diretório montado pelos containers ativos da VPS.
`cache-unpacked/` é fonte de edição: sua presença não autoriza reconstruir o
pacote ativo. Não versionar cache, saves, volumes, credenciais ou logs.

1. Conferir `git status` em cada repositório antes de editar ou atualizar.
   Configurações pessoais ficam nos arquivos locais ignorados pelo Git.
2. Ligar o Docker Desktop e, em `drynor-world-server/`, executar
   `docker compose -f docker-compose.local.yml up -d --wait`. Isso inicia
   apenas MongoDB; preserva seus dados no volume Docker.
3. Com JDK 26, construir **um projeto Gradle por vez**: `gradlew.bat installDist`
   no world e no lobby. O composite build compila o core necessário.
4. Iniciar o lobby por `drynor-lobby-server/start.bat` ou pela tarefa Gradle
   `run`. Conferir `http://127.0.0.1:4040/ready` com resposta `ready: true`.
5. Iniciar o world pela tarefa `gradlew.bat run` no diretório do world, com
   `DRYNOR_CACHE_BUILD=false`. Conferir listeners 43595/43596 e o registro do
   world no lobby. O world depende de `data/worldConfig.json` local com
   `lobbyIp`, `lobbyApiPort` e chave correspondentes ao lobby.
6. Com JDK 19, iniciar o cliente via `drynor-client-v2/start.bat` ou
   `gradlew.bat run --args="127.0.0.1 43594"`. Confirmar login no lobby,
   entrada no world, saída para lobby e nova entrada. Builds/testes não
   substituem esse teste visual.

Os CMDs locais podem ficar visíveis para logs. `start.bat` do lobby também
verifica o MongoDB; não iniciar um segundo Compose na mesma porta. Para parar
somente o banco e preservar o volume, executar
`docker compose -f docker-compose.local.yml down` no diretório do world.
Nunca usar `down -v` como rotina.

## Verificação antes de integrar ou entregar

- Executar `gradlew.bat test` e o build apropriado em cada repositório alterado,
  sempre sequencialmente entre world, lobby e core. Usar JDK 19 para o cliente.
- Para rede e cache, conferir contratos de pacote, catálogo da revisão 727 e
  leitura do cache empacotado. Uma alteração no cache deve ser construída em
  saída separada, comparada e promovida com os serviços parados.
- Registrar separadamente: testes automatizados, smoke de inicialização e
  fluxos confirmados pelo usuário no cliente. Não reutilizar confirmação de
  uma versão antiga como evidência da nova.
- Respeitar as instruções do `AGENTS.md` do repositório dono e as skills em
  `drynor-core/.agents/skills/`. Preservar gameplay Drynor e DrynorLite/RuneLite.

## Deploy e release

O fluxo normal de servidores agora está em `deploy/`: Dockerfiles com JDK 26
compilam e testam diretamente na VPS. O comando instalado em
`/home/neto/drynor-rsps/deploy.sh` recebe `world`, `lobby`, `status`, `check` ou
`rollback world`/`rollback lobby`. Ele atualiza os repositórios originais por
fast-forward e cria imagens com a fonte e o core identificados por commit.
Não é necessário enviar JARs do PC. O deploy para o jogo durante o build para
liberar memória na VPS e preserva um rollback antes de instalar a imagem.
Os testes usam MongoDB temporário na porta local 27018, sem acesso às bases
de produção. Dados de gameplay do world são sincronizados com a imagem;
cache, configuração do GE, arquivos de configuração, spawns adicionados em
execução e bindings de conteúdo ficam preservados e exigem entrega específica
quando forem alterados. `check` apenas valida a estrutura/configuração.
As instruções manuais anteriores ficam como referência de recuperação.

A VPS ativa usa o checkout `/home/neto/drynor-rsps/` e monta seu `cache/` nos
containers de lobby e world. Verificar a configuração e os mounts **dos
containers em execução** antes de publicar; arquivos Compose históricos em um
checkout local podem descrever outro stack. O banco e os saves da VPS não
participam de limpezas locais. Um deploy exige builds aprovados, revisão da
diferença, backup/rollback apropriado, atualização coordenada de lobby/world e
verificação de `/ready`, portas, registro do world e login externo. Não tomar
um build local bem-sucedido como autorização ou confirmação de deploy.

Ao publicar NPCs novos, sincronizar também `world/data/npcs/combatdefs/`,
`world/data/npcs/drops/` e `world/data/npcs/spawns/` a partir do mesmo commit
do JAR. Antes do reinício, comparar contagem e SHA-256 dos arquivos da área no
checkout com os arquivos realmente montados em `/app/data` pelo container.
Após reiniciar, testar um NPC atacável recém-publicado: o fallback de combate
do world tem apenas **1 HP**, então um spawn visível não comprova que sua
definição de combate foi implantada. Preservar cópia dos dados anteriores
para rollback e não sobrescrever saves ou dados do MongoDB.

O cliente distribuível pertence a `drynor-client-v2/`. O repositório
`drynor-client-release` contém os JARs/manifests publicados; alterar o código
do cliente não atualiza automaticamente uma release. A versão e os endpoints
devem ser conferidos no artefato efetivamente publicado antes de entregá-lo.
As portas públicas atuais são lobby TCP 43594 e world TCP 43595; HTTP 4040 e
43596 são serviços internos e sua exposição deve seguir a configuração real
do túnel/proxy.
