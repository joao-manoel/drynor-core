# Drynor: retirada das APIs antigas

Trabalho na branch `codex/native-api-migration` dos repositórios core, lobby,
world e client. Os checkouts originais são utilizados; a `main` permanece no
checkpoint jogável até a conclusão dos gates abaixo.

## Etapa validada em 14/10/2026

- Clãs usam `org.drynor.core.model.Clan`, `ClanRank`, `ClanSetting` e
  `ClanMemberData` no domínio/persistência do lobby e no gameplay do world.
- O decoder nativo distingue configurações de clã do arquivo 54 (opcode 2)
  das variáveis do arquivo 47 (opcode 3).
- Migração de documentos preserva os 282 campos/configurações históricos,
  permissões, motto, membros e códigos de fórum de 64 bits. Campos opacos
  ficam preservados no documento; somente ids definidos pelo cache vão ao client.
- Snapshots da interface utilizam os codecs atuais, os bitfields do cache,
  nomes personalizados de membros offline e ordenação consistente dos membros.
- `ClanAction.CheckName/Create` são comandos tipados; não dependem do antigo
  encaminhamento de `ClanCheckName`. Autorização verifica presença e origem do world.
- A entrega ao world segue `updateclan → updatesocial → sendpacket`, na FIFO
  limitada do jogador. O callback social contém somente username/social.
- O world aplica mutações de clã/social no thread de gameplay. Respostas de
  falha aparecem ao jogador; callbacks liberam o bloqueio do input.
- Datas gravadas por Gson são lidas corretamente e passam a ser escritas como
  instantes ISO. A implementação usa ano calendário e locale explícito.
- Logging dos consumidores foi portado ao logger atual, mantendo um callback
  de persistência de erros controlado pela aplicação.
- O client/RuneLite não foi substituído. O usuário confirmou login jogável,
  criação do clã, entrada automática e abertura das configurações.

Validação: core 381 testes, CS2 14, lobby 93 e world 8, todos sem falhas/erros.
As probes `verifyClanCacheMigration`, `verifyClanCommandHttp` e
`verifyNativeClanMigration` passaram com cache/Mongo/HTTP locais. A comparação
opcional com `/usr/bin/bzip2` continua indisponível no Windows.

## Modelos de itens e interfaces: 14/10/2026

- Interfaces, scripts CS2, variáveis e snapshots de inventário do world usam
  DTOs/codec nativos. Testes diferenciais verificam framing e avanço do ISAAC,
  slots vazios, stacks grandes e flags de contêiner. Sessão e framing continuam
  no adaptador de compatibilidade até a migração do transporte.
- Os consumidores de itens do gameplay, banco, equipamento, lojas, drops, logs,
  eventos/plugins e inventário utilizam `Item`, `GroundItem` e `ItemDefinition`
  atuais. O modelo lê os dois nomes de metadados e exclui slot/owner dos saves.
- `verifyNativeItemCache` comparou 25.494 definições (preços, bônus, requisitos,
  notas, equipamentos e cores) e 166 recompensas de lealdade sem diferenças.
- Constantes de gameplay, atributos, flags de interface e o enum de bônus
  utilizam tipos atuais. O lookup de bônus de NPC ainda tem uma conversão no
  limite do decoder antigo, até a migração dos NPCs.
- O usuário confirmou retirada do banco e drop/pickup. O problema do item
  descartado vinha da regra antiga para ADMIN. A configuração local habilita
  `allowAdminGroundItems`; seu padrão é false. Quando bloqueado, o comando de
  drop mantém o item e informa o jogador.
- Foram retiradas 43 ferramentas antigas sem consumidores de gameplay. Os
  auxiliares usados por comandos e NPCs continuam; cache/CS2 devem ser editados
  pelas ferramentas atuais do core e pela árvore de fontes do cache.
- A lista de arquivos de produção com referências `com.rs.lib`/`com.rs.cache`
  caiu de 1.328 para 1.078. Isso é somente um indicador de andamento.
- `NativePacket` ainda conecta os codecs atuais ao transporte anterior.
  `GroundItemRuntime` e `NativeZonePacket` foram retirados nas etapas seguintes.
- Builds locais validados podem ser executados a partir de snapshots compilados
  ignorados pelo Git, evitando mudanças de classes durante os testes manuais.
  Código-fonte continua nos checkouts originais da branch.

## Modelos e mapa: etapa de 15/10/2026 em validação

World usa Tile, Animation, SpotAnim, Projectile, WorldObject, ObjectShape,
MapRegion, RegionSize, Vec2 e Vars atuais. NPC/Object/Enum/Struct/VarBit/Animation/
BAS são lidos pelos decoders atuais; a ponte GroundItemRuntime foi removida.
Objetos, efeitos, projéteis e construção de cenas usam codecs atuais, com
transporte ainda intermediado por NativePacket. O serviço
MapXteas do core fornece chaves para mapas e pacotes.

Foram comparados 15.663 NPCs, 73.903 objetos e 17.225 animações no cache real.
Regras de portas de dungeoneering e classificação de mortos-vivos continuam no
world. Durações de animação são lidas sem sinal; TileJson lê coordenadas do
conteúdo e grava o formato atual. Testes atuais: core 384, CS2 14, lobby 93 e
world 17, sem falhas. A probe inicializa quests/conquistas e verifica as cinco
opções dos NPCs e a customização/reset de objetos. Login, movimento, portas,
ataque e dano foram confirmados no cliente antes da etapa de áudio/batching.

Os namespaces do projeto são `org.drynor.*`; propriedades e identificadores de
build usam o nome Drynor. Core, CS2, tools, lobby e world foram recompilados com
essa identidade. O cliente mantém seu código funcional e a integração RuneLite.

A contagem de arquivos de produção do world com referências antigas caiu para
200; isso não substitui os gates abaixo. Após a troca do namespace e o batching
de zonas, o usuário confirmou login, banco, drop/coleta, combate e aba de clãs.

## Gates ainda abertos

Etapa seguinte: world/lobby compartilham comandos tipados de social e clãs,
incluindo mensagens polimórficas fechadas. O world não encaminha mais objetos
de decoder pelo HTTP; settings de clã são deltas e o write integral foi retirado.
O leitor de frases quick-chat usa o cache atual. Foram validados 386 testes do
core, 94 do lobby e 18 do world, além das probes de cache e HTTP/Mongo isolado.
O transporte HTTP de saída do world usa HttpClient do JDK com limites e timeout.
Isso não conclui a migração de Account/Rights/FC, sessão, JS5 ou persistência.

1. Migrar os demais comandos de clã, contas/social e contratos de gateway.
2. Migrar consumidores de definições/modelos, JS5, Huffman e cache para o backend atual.
3. Migrar encoders/decoders, sessão, login e entity updates para protocolos tipados.
4. Migrar persistência/utilitários restantes preservando economia e conteúdo.
   O usuário autorizou descartar saves se necessário para adotar o padrão atual.
5. Capturar os goldens dos testes diferenciais e retirar a dependência de teste antiga.
6. Remover o módulo/dependências `legacy-compat` e verificar ausência das classes
   antigas nos artefatos distribuídos. Renomear ou copiar classes antigas não
   satisfaz este gate.
7. Validar cliente real: relog, inventário/banco/equipamento, combate, interfaces,
   friends/ignores/PM/quick-chat, FC, convite/ranks/guest/clans e dois worlds.
8. Atualizar CI/Docker/publicação, fazer merge na main e publicar no GitHub.

A migração completa não está concluída. As branches ainda dependem de
`legacy-compat` para os consumidores não portados. Os leitores dos formatos
históricos de dados são necessários para preservar os saves e não autorizam
manter o runtime antigo como substituto do core atual.

AccountView/Rights/Social/FriendsChat agora substituem os modelos antigos no gameplay do world; o lobby ainda possui sua migração interna pendente. AccountViewJson limita a projeção aos dados públicos e usa construção normal. Vars sincroniza mudanças por snapshot atômico, com preservação das alterações durante entrega e reenvio após falha. LoginCacheChecksums e InventoryDefinitions leem o backend atual. Login/corrida foram confirmados manualmente após serializar a inicialização do jogador na thread do world. A identificação de processos no HTTP trata sessões órfãs após reinício. Os gates de transporte, persistência e remoção de legacy-compat continuam abertos.

### Native gateway presence and social state (2026-10-15)
The authenticated WebSocket now carries player online/offline, fenced presence snapshots, account/social bootstrap, friends/ignores, private/quick-chat messages and friends-chat replicas. Messages are bounded to 1 MiB; one writer preserves ordering. Snapshot tickets are single-use, expire locally and cannot overwrite a newer login/logout or presence on another world. Reconnect clears stale FC state before rebuilding current channels. World snapshots and state application run on the gameplay thread; retired connections/sessions cannot update replacement players. Once a world adopts native presence, its HTTP presence routes are rejected instead of providing a competing fallback.

Automated checks passed: core wire fixtures, lobby real WebSocket/Mongo recovery and world lifecycle tests, including saturated logout admission forcing reconnection and an empty replacement snapshot without command replay. Client validation of this phase is pending. Clan commands/state and world registration still use HTTP; client TCP/session/login/entity updates, JS5 and compatibility persistence remain open. This phase does not complete removal of legacy-compat.

### Comandos de clã no gateway e retorno ao lobby (2026-10-15)
ClanCommands concentra a aplicação dos comandos tanto no HTTP de compatibilidade quanto no WebSocket autenticado. O world agora usa o mesmo writer, fila limitada e IDs Long monotônicos para social e clãs, sem repetir mutações após queda. Respostas de clã validam ID, usuário e ação; respostas de social não podem concluir pedidos de clã. Testes reais cobrem resultados misturados, presença/origem, replay e IDs acima de 32 bits.

O usuário confirmou login, amigos e friends chat da etapa anterior, mas encontrou retorno ao lobby caindo na tela de login. O world agora aguarda o callback da tentativa de save e a confirmação de PlayerOffline antes de remover o jogador e enviar LOGOUT_LOBBY; falha no gateway resulta em logout completo. Isso não altera a semântica histórica de erro do save, cujo callback ainda não comprova gravação bem-sucedida. Teste de socket verifica que o callback de saída não antecede o ack. Core 391, lobby 100 e world 29 testes passaram. Retorno ao lobby e comandos de clã desta versão aguardam teste no cliente. Estado/interface de clãs e registro de world ainda usam HTTP; os demais gates da migração permanecem abertos.

### Réplicas nativas de clã (2026-10-15)
ClanReplica entrega conta, documento, nomes e canal em uma mensagem ordenada; ClanNotification seleciona o canal pelo destinatário, inclusive quando o remetente é visitante. O bootstrap limpa os canais antigos. O world atualiza cache, variáveis, aparência e UI na thread de gameplay, sem enviar vars do clã principal ao limpar apenas visitante. ClanService fornece estado atual sob seu lock até os enqueues: publicações antigas não substituem configurações ou clã recriado, nem um clear atrasado apaga rejoin principal/visitante. Testes reais WS/Mongo cobrem esses casos. Core 391, lobby 101 e world 29 testes passaram; uma execução do teste HTTP de corpo excedente teve EOF, e a execução completa seguinte passou. Esta etapa ainda aguarda teste no cliente. O usuário confirmou a versão anterior: retorno ao lobby sem senha, novo login e alteração de motivo/cores funcionaram.

### Consulta de clãs e API operacional atual (2026-10-15)
ClanFetch/ClanFetchResult usam IDs Long e o mesmo limite/deadline/writer dos comandos. A consulta e sua resposta são ordenadas com as réplicas sob o lock do clã; callbacks/cache do world executam na thread de gameplay. Clear de friends chat tem reset de clãs independente, com regressão coberta.

WorldHttpApi em Kotlin/Ktor substitui WorldAPI/WebAPI e mantém /api/players, telemetria e logout administrativo. Foram removidos /updateclan, /updatesocial, /updatefc, /sendpacket e /forwardpackets do world. Autenticação é constant-time, corpo limitado a 1 MiB, até 64 pedidos, timeout e encerramento explícito. Logout administrativo usa uma fila cancelável de 64 posições processada no tick; timeout remove ações pendentes, evitando mutações atrasadas. Telemetria usa filas concorrentes. ClanTransportJson passou apenas para fixtures de teste; o launcher não registra mais os adapters polimórficos de pacotes.

Validação: core 391, lobby 101 e world 32 testes sem falhas. A regressão HTTP 413 do lobby foi corrigida com descarte limitado sob deadline e Connection: close; o teste repete quatro uploads na borda. Probe real de cache/clãs passou. O JAR contém a API atual e não contém com.rs.web.WorldAPI nem com.rs.net.ClanTransportJson; os cinco endpoints retirados retornam 404 no runtime. O usuário confirmou login, motivo/cores, entrada/saída do friends chat preservando clã, retorno ao lobby e novo login. Convite/mensagem com segunda conta e regressão de dois worlds não foram confirmados separadamente nesta etapa. Persistência, contas internas do lobby, registro/autenticação de world, TCP/sessão/entity updates e JS5/Huffman continuam nos gates; legacy-compat ainda não pode ser removido nem a migração integrada na main.

### Persistência nativa do world (2026-10-16)
MongoDocumentStore/MongoRepository substituem DBConnection/DBItemManager no world, com banco explicitamente configurado, ping de inicialização, índices verificados, até 64 operações admitidas, timeout de operação e encerramento com drenagem. Formatos de jogadores, GE, highscores e logs continuam preservados. O registro de erros pertence ao world e limita diagnósticos a tipo e stack, sem mensagens arbitrárias.

Saves de jogador capturam o documento antes do I/O e seguem FIFO por usuário, inclusive os síncronos. O callback só confirma sucesso após a gravação real. Falha de leitura no login retorna indisponibilidade e não cria um jogador vazio. Saída mantém entidade e presença reservadas durante falha de save, com retry ligado à entidade; captura de presença inclui apenas finalizados explicitamente reservados. O desligamento programado aguarda saídas sem bloquear ticks e cancela após dois minutos de pendência.

Validações automatizadas e runtime desta etapa estão em andamento; não considerar confirmação de cliente da versão anterior como teste desta persistência. Persistência de contas internas do lobby, política de falhas das operações econômicas, transporte TCP/sessões/entity updates, registro/autenticação do world e JS5/Huffman ainda impedem remover legacy-compat e integrar a migração completa na main.

Verificação automatizada da persistência concluída: 393 testes do core e 34 do world passaram; probe real do MongoDB passou usando bancos temporários próprios, incluindo isolamento, falha explícita e rejeição após close. Regressões cobrem FIFO, barreira de save, progresso independente entre jogadores, reserva durante falha, retry e substituição de sessão. Build final com bloqueio de login no desligamento passou. Runtime local iniciou com Mongo saudável e gateway conectado; confirmação do cliente para esta versão ainda pendente. Nenhuma alteração no cliente/RuneLite, merge ou publicação realizados nesta etapa.

Validação no cliente concluída (2026-10-16): o usuário confirmou login no world, depósito/retirada no banco, movimentação de item no inventário, retorno ao lobby e novo login, com itens e posição preservados, sem erro de sessão ou crash. A etapa de persistência nativa do world está validada nesse escopo; os gates restantes acima continuam abertos.

### Modelos nativos de conta e social no lobby (2026-10-16)
Serviços de contas, permissões/punições, presença, amigos/ignorados, mensagens, friends chat, clãs e login do lobby agora usam Account/Rights/Social/FriendsChat/FriendsChatRank do core atual. Adaptadores de encoders antigos recebem somente projeções explícitas sem credenciais em LegacySocialModels; esses encoders, transporte TCP/JS5 e os DTOs de callers antigos ainda mantêm legacy-compat. O cliente/RuneLite e os formatos de pacotes não foram alterados.

O nome vazio da conta nova mantém identidade de login e indicador separado de seleção de nome. A persistência omite e-mails vazios para preservar o índice único esparso; save de domínio usa updates atômicos que não tocam credenciais, evitando sobrescrever a conversão concorrente de SHA-1 para Argon2. PublicAccountJson limita as respostas HTTP à projeção pública, sem senha, hash, histórico, e-mail ou recuperação. Updates HTTP de permissões, punições e filtros exigem campos/tipos explícitos. Ranks FC inválidos são rejeitados; sair explicitamente mantém currentFriendsChat=null.

Verificação automatizada: 103 testes e build passaram, incluindo Mongo real, whitelist de resposta e pedidos HTTP inválidos, preservação de SHA-1 antes da autenticação e Argon2 após save, golden bytes de login sem nome, amigos, ignorados, privacidade e FC/clãs. Validação desta versão no cliente ainda pendente; não equivale à conclusão da migração completa ou autorização para merge na main.

Verificação adicional desta versão: probes verifyClanCacheMigration e verifyClanCommandHttp passaram contra o cache real e Mongo temporário. Cobrem configurações/canais de clã, comandos sociais/FC, autorização e ordem de estado/interface no adaptador remanescente. A revisão de protocolo não encontrou bloqueios após preservar currentFriendsChat=null também em save→reload. Runtime imutável native-accounts-final-20261016 iniciou e o world conectou ao gateway. Os dois testes solicitados no cliente continuam pendentes.

Validação do cliente concluída (2026-10-16): o usuário confirmou login no world, adicionar/remover amigo, abrir/configurar friends chat e clã, voltar ao lobby e entrar novamente. Confirmou separadamente criar conta de teste, escolher nome do personagem, entrar no world e sair/entrar novamente. Etapa de modelos internos nativos de conta/social validada nesse escopo, com RuneLite preservado. Transportes TCP/sessão, JS5/Huffman, registro/autenticação do world e adaptadores residuais continuam nos gates da migração completa; sem merge/publicação na main.

### Registro e autenticação do world pelo gateway (2026-10-16)
O world nativo usa WorldHello(register=true) com UUID de processo para registro, metadados completos e origem real do socket. WorldRegistrationService compartilha a limpeza de presença/canais com o adaptador HTTP. O registry valida atividade efetiva, host, país e portas antes de publicar; a instalação da conexão revalida a instância sob o mesmo monitor. Clientes antigos mantêm register=false e seu heartbeat HTTP, sem alteração silenciosa de metadados.

A conexão Ktor mantém ping/pong e renova a lease a cada 10 segundos por tarefa independente dos comandos de domínio. Assim, uma consulta lenta não faz expirar o world saudável. O refresh só aceita a origem e a instância atuais; encerra com a conexão. O world não usa mais LobbyHeartbeat HTTP.

WorldLoginAuth/Result usam a fila limitada e IDs Long existentes, com status SUCCESS/INVALID_TOKEN/BANNED/UNAVAILABLE e somente AccountView público. O token é consumido uma vez; queda ou timeout não repetem autorização. O cliente valida ID, tipo, username e relação status/conta. WorldLoginDecoder aguarda sem bloquear Netty, impede autorização duplicada e revalida canal/decoder, shutdown e jogador existente antes da inicialização na thread do world. O world não chama mais authworldlogin HTTP.

Lobby: 107 testes e build passaram, incluindo socket real, replay, banimento, indisponibilidade, metadados, leases e operações lentas. Core/world final e validação no cliente ainda em andamento. Consultas administrativas/contas e atualizações de direitos/punições ainda usam adaptadores HTTP; TCP/sessões/entity updates e JS5/Huffman continuam pendentes. Esta etapa não conclui a migração completa nem libera merge na main.

Verificação final desta etapa: core 394 testes, lobby 107 testes e world 35 testes sem falhas; builds e shadowJar passaram. O artefato do world não contém mais com.rs.net.LobbyHeartbeat. Runtime local iniciou com world 1 registrado pelo socket, sem heartbeat HTTP, e a API de jogadores respondeu. Cliente reaberto para validação de login, banco/clãs e retorno ao lobby; confirmação humana desta versão ainda pendente.

Validação do cliente concluída (2026-10-16): o usuário confirmou login, seleção/entrada no world, banco, clãs, retorno ao lobby e novo login sem erro de sessão ou crash. O world permaneceu registrado após o TTL anterior apenas com a lease do gateway. Registro e autorização de login do world principal estão validados neste escopo; sem mudança no cliente/RuneLite e sem merge na main. Permanecem adaptadores de consultas/permissões/punições, transporte TCP/sessão/entity updates e cache JS5/Huffman.

### Consultas de conta e moderação pelo gateway (2026-10-16)
AccountLookup/Result e AccountMutationRequest/Result substituem as últimas chamadas HTTP de conta do world principal. As respostas usam IDs Long, limite e timeout compartilhados, validação de destino/tipo e nenhuma repetição automática de mutações. O lobby continua dono da conta; worlds autenticados mantêm a fronteira de confiança existente e os comandos verificam permissões no world. Consultas retornam apenas AccountView público, sem credenciais.

Cargos e punições só são confirmados depois da gravação. Banimento e silêncio são deltas independentes; falha não aplica estado local especulativo. Escrita, publicação e resposta são ordenadas por usuário, inclusive para adaptadores HTTP de worlds ainda não migrados. Callbacks revalidam entidade/sessão e alteram gameplay apenas no tick. Consultas offline por nome visível resolvem o username canônico antes de ler o save.

AccountUpdate e respostas de mutação atualizam identidade/cargos/punições preservando social. Réplicas de social, friends chat e clã preservam os campos de autoridade, impedindo rollback por projeção antiga. Reconexão atualiza a conta sob a mesma ordenação; jogadores banidos recebem a atualização mesmo sem presença ativa, antes da reconciliação.

O world removeu LobbyHttpClient, LobbyHeartbeat e o facade Java genérico; LobbyCommunicator em Kotlin expõe somente operações nativas. Inspeção do JAR confirmou ausência dos dois transportes removidos. O lobby ainda mantém adaptadores HTTP para consumidores antigos; legacy-compat permanece necessário para TCP/sessão, decodificação/entity updates e cache JS5/Huffman, além dos demais gates econômicos e multiworld.

Verificação automatizada: core 395, lobby 110 e world 35 testes sem falhas; builds passaram. Testes com Mongo/socket reais cobrem consultas offline, ausência sem criação, deltas, rejeição de escrita, concorrência/publicação ordenada, replay e recuperação de banimento na reconexão. Revisão de protocolo concluída. Runtime local atualizado com gateway conectado; cliente reaberto. Validação humana desta versão pendente. Sem mudança no cliente/RuneLite, merge ou publicação na main.

### Retorno de validação e correção da lista de membros (2026-10-16)
O usuário confirmou os fluxos gerais da etapa de contas e o teste de silenciar/remover silêncio. Relatou texto incorreto em Clanmates e falha ao alterar National Flag; esses dois fluxos ainda não estão validados.

O codec ClanSettingsFull agora transmite somente membros reais. O padding até cinco membros criava FILLER_CHARACTER como contas visíveis ao leitor ClanSettings do cliente; esse leitor não exige contagem mínima. Fixture independente verifica opcode 137, bytes de um membro Pequi com rank/flags e contagens 0/1/5/6, sem preenchimento artificial. Testes do core passaram. Renderização e seletor de bandeira aguardam confirmação no cliente; não houve alteração no cliente/RuneLite.

Seletor de bandeira: o cache ativo cria ícones em 1089:30, confirmação em 1089:26 e observa varp 2149 para o destaque. O world inicializa a seleção atual, publica a varp ao selecionar, habilita a confirmação e grava antes de fechar; o ack só fecha se o seletor ainda estiver aberto. Revisão independente conferiu esses hooks sem alterar cache ou cliente. Core 396, lobby 110 e world 35 testes passaram; renderização/seleção/salvamento ainda exigem teste humano desta versão.
