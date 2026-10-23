# Organização do workspace Drynor

Para iniciar, testar e entregar os serviços, use [WORKSPACE_RUNBOOK.md](WORKSPACE_RUNBOOK.md).
Este arquivo trata de diretórios, cache e retenção de dados.

## Diretórios mantidos

| Caminho | Papel | Regra |
|---|---|---|
| `drynor-core`, `drynor-world-server`, `drynor-lobby-server`, `drynor-client-v2` | Repositórios atuais | Preservar código e alterações locais; verificar `AGENTS.md` de cada um. |
| `drynor-launcher`, `drynor-bot-discord`, `site` | Produtos Drynor | Preservar código e configuração própria. |
| `cache/` | **Único cache empacotado de execução local** | Sincronizar com o cache ativo montado nos containers da VPS; não versionar. |
| `cache-unpacked/` | Fonte editável de cache/CS2 | Pode diferir da produção; nunca tomar sua presença como pedido de rebuild. |
| `Referencia/` | Fontes externas de consulta | Somente leitura, salvo pedido explícito. |
| `.local-deploy/` e `*/.local-runtime/` | Ferramentas e estado temporários locais | Limpar logs e builds obsoletos sem apagar scripts úteis, credenciais ou dados. |

Saves, bancos MongoDB, volumes Docker e arquivos de configuração local não são
artefatos de build. A limpeza de arquivos gerados não os inclui.

## Cache canônico e sincronização

O cache usado pelos dois containers Drynor na VPS é o diretório
`/home/neto/drynor-rsps/cache`, montado em `/cache`. O `dat2` e todos os
`idx*` formam um conjunto indivisível: copiar só `dat2` deixa os índices
incompatíveis. O cache Gielenor e os caches dentro de releases/backups não são
a origem ativa do Drynor.

Em 23/10/2026, os 39 arquivos do pacote ativo foram copiados e conferidos
individualmente por SHA-256. O `main_file_cache.dat2` local e remoto têm hash
`8b4eee02f0d82b12374528d23a6ab1167aaa17d1da18dd1dbddde5e6a7e6326c`.
O arquivo `cache/SHA256SUMS.txt` registra os hashes do conjunto. Os builds
`installDist --offline` do world e do lobby passaram com JDK 26. Com MongoDB
local saudável, o lobby respondeu `200` em `/ready` e o world abriu as portas
43594, 43595 e 43596. O login no cliente ainda requer validação interativa.

1. Verificar os mounts dos containers, a lista de arquivos e os SHA-256 do
   diretório ativo da VPS. Não inferir a versão apenas pelo nome de um release.
2. Baixar todos os arquivos para um diretório temporário fora de `cache/` e
   conferir os hashes individuais. Nenhum serviço local deve ler a cópia parcial.
3. Parar lobby e world locais, mover o pacote anterior para uma área temporária
   e promover o pacote verificado para `A:/dev/drynor/cache/`.
4. Iniciar lobby e world com o build automático desabilitado; conferir `/ready`,
   portas de login/world e um login real no cliente. Depois remover o pacote
   anterior, se a validação passou.

`cache-unpacked` permanece para desenvolvimento. Uma alteração nele deve ser
construída em outra saída, verificada por leitura do pacote e comparada ao cache
ativo antes de substituir o pacote de execução. Nunca empacotar sobre `cache/`
enquanto lobby ou world estiverem ativos. `DRYNOR_CACHE_BUILD=true` habilita o
builder legado explicitamente; o core moderno só usa fonte quando
`CACHE_SOURCE_PATH` for configurado. O padrão é servir o pacote existente.

## Limpeza e branches

- Apagar caches empacotados duplicados e logs/builds antigos gerados localmente
  somente depois de identificar seus consumidores e confirmar o caminho absoluto.
- Preservar `cache-unpacked`, repositórios, fontes, saves, volumes, backups da
  VPS, licenças, scripts operacionais e configuração/segredos locais.
- Em cada repositório, excluir uma branch local somente se sua ponta já estiver
  contida em `main`, sem commits exclusivos, worktree associado ou alterações
  pendentes. Branches não integradas permanecem; branches remotas exigem revisão
  independente antes de qualquer remoção.
- `build/` e `.gradle/` são regeneráveis, mas não devem ser apagados durante uma
  execução local que dependa deles. Não confundir uma compilação bem-sucedida
  com validação do fluxo no cliente.

As skills versionadas em `drynor-core/.agents/skills` guardam os critérios de
cache e conteúdo. Este documento registra os caminhos e o procedimento local;
atualizá-lo quando a topologia de deploy mudar.

## Estado da limpeza local

Em 23/10/2026, o usuário removeu manualmente as duas duplicatas de cache
(`.local-deploy/cache-old-20261022/` e `drynor-world-server/data/cache/`) e os
builds antigos `.local-deploy/client-release-1.0.8/`,
`.local-deploy/client-release-1.0.9/` e `.local-deploy/client-v1.0.2/` a
`.local-deploy/client-v1.0.4/`. A checagem confirmou que não existem mais.

O arquivo `.local-deploy/drynor-release.tar` e todos os 82 arquivos `.log` ou
`.err` sob `.local-deploy/` também foram removidos manualmente pelo usuário.
A checagem posterior encontrou zero logs/erros restantes e confirmou que
`cache/main_file_cache.dat2` continua presente. A revisão automática de
comandos tinha bloqueado a remoção pelo agente. Não incluir arquivos
`.bundle`: podem guardar histórico Git. Os diretórios `.local-deploy/drynor-client-release/`,
`ssh-deps/`, `python-libs/` e os saves continuam preservados. As branches locais
cuja ponta já estava contida em `main` foram removidas; branches remotas não
foram alteradas.
