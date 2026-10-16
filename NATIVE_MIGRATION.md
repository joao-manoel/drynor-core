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
