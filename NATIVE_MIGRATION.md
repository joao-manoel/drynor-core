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
