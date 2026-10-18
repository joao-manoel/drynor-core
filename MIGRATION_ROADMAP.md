# Roadmap da migração nativa Drynor

Estado em 18/10/2026: core, lobby e world estão na branch `codex/native-api-migration`. O transporte, o login, as filas, vários comandos de entrada/saída e a verificação de reflection do world usam o core atual. Os fluxos citados em `NATIVE_MIGRATION.md` e `../drynor-world-server/NATIVE_API_MIGRATION.md` tiveram testes automatizados e confirmações específicas no cliente. Isso ainda não significa que o `legacy-compat` foi removido.

A publicação solicitada pelo usuário nesta data é uma **versão parcial jogável**. Integrar a branch à `main` e implantá-la na VPS não encerra esta lista. Cada item abaixo precisa de teste automatizado e, quando afeta o protocolo ou gameplay, validação no cliente moderno com DrynorLite/RuneLite preservado.

1. **Contrato de entrada do world.** Retirar `com.rs.lib.net.ClientPacket` do decoder, comandos nativos, eventos e consumidores de gameplay. Preservar opção, slot, item e transformações de cada opcode 727. Hoje aproximadamente 66 arquivos de produção do world ainda importam esse enum.
2. **Saída e modelos auxiliares do world.** Remover os usos restantes de `ServerPacket`, `PacketEncoder`, streams e DTOs de compatibilidade. Comparar bytes de cada codec com o cliente e manter limites de fila, ordem ISAAC e ciclo de vida da sessão.
3. **Transporte TCP do lobby.** Substituir `Session`, `Decoder`, encoders de login, lobby e social e adaptadores de corpos por sessão/framing do core atual. Cobrir login, criação de conta, seleção de world, renovação de token, saída/reentrada e reconexão.
4. **Serviços e modelos do lobby.** Concluir a migração de contas, amigos/ignorados, PM, quick-chat, friends chat, clãs, presença, registro de worlds, gateway e endpoints HTTP. Preservar a autoridade do lobby e a projeção para múltiplos worlds.
5. **Cache e conteúdo.** Retirar leitores e contratos antigos ainda usados por JS5, Huffman, quick-chat, interfaces/quests e utilitários. Comparar definições e cache empacotado com a revisão 727. Corrigir a exibição da National Flag nas demais interfaces, já identificada e adiada pelo usuário.
6. **Eliminar `legacy-compat`.** Após migrar todos os consumidores de lobby e world, remover as dependências Gradle e os adaptadores restantes. Inspecionar os artefatos finais e executar todas as suítes relevantes.
7. **Gates finais de operação.** Testar duas contas, banco, inventário/equipamento, drops, combate, social/clãs, saída para o lobby, reconexão, restart, presença multiworld e persistência. Validar cliente moderno, desempenho e plugins DrynorLite/RuneLite sem sobrescrever customizações.
8. **Fechamento.** Atualizar documentação de compatibilidade, revisar diferenças da `main` e publicar o release final somente quando os itens anteriores estiverem concluídos.

Os repositórios são independentes. Commits, builds e tags de core, lobby e world devem registrar a mesma combinação de revisões para que um rollback não misture protocolos incompatíveis. Segredos, bancos, caches locais e artefatos gerados não entram no Git.
