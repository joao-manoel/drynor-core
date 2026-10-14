# Drynor: retirada das APIs antigas

Trabalho na branch `codex/native-api-migration` dos repositórios core, lobby,
world e client. Os checkouts originais são utilizados; a `main` permanece no
checkpoint jogável até a conclusão dos gates abaixo.

## Etapa validada em 14/10/2026

- Clãs usam `org.darkan.core.model.Clan`, `ClanRank`, `ClanSetting` e
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

Validação: core 380 testes, CS2 14, lobby 93 e world 4, todos sem falhas/erros.
As probes `verifyClanCacheMigration`, `verifyClanCommandHttp` e
`verifyNativeClanMigration` passaram com cache/Mongo/HTTP locais. A comparação
opcional com `/usr/bin/bzip2` continua indisponível no Windows.

## Próxima etapa em andamento

- Interfaces, scripts CS2, variáveis e snapshots de inventário do world usam
  DTOs/codec nativos. Testes diferenciais verificam framing e avanço do ISAAC,
  slots vazios, stacks grandes e flags de contêiner. Sessão e framing continuam
  no adaptador de compatibilidade até a migração do transporte.
- O modelo nativo de Item lê `metaData` histórico e `metadata` atual no Gson,
  preservando cargas, propriedades personalizadas e mapas vazios distintos de
  metadados ausentes. Essa preparação ainda não troca os consumidores do world.
- Builds locais validados podem ser executados a partir de snapshots compilados
  ignorados pelo Git, evitando mudanças de classes durante os testes manuais.
  Código-fonte continua nos checkouts originais da branch.

## Gates ainda abertos

1. Migrar os demais comandos de clã, contas/social e contratos de gateway.
2. Migrar consumidores de definições/modelos, JS5, Huffman e cache para o backend atual.
3. Migrar encoders/decoders, sessão, login e entity updates para protocolos tipados.
4. Migrar persistência/utilitários restantes sem alterar saves, economia ou conteúdo.
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
