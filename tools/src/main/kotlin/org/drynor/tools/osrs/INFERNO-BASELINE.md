# Inferno — diagnóstico de 18/10/2026

Branch de trabalho do core, cliente e world: `codex/osrs-maps-inferno`.

## Execução verificada

- JDK: Temurin 26.0.2.1+1 local.
- `:tools:test`: aprovado; quatro testes do scanner de terreno, zero falhas.
- `:tools:run --args="osrs inventory-inferno A:/dev/gielenor/gielenor-source/data/cache A:/dev/drynor/cache A:/dev/drynor/drynor-core/build/osrs-inferno/inventory.json"`: aprovado.
- Relatório local em `build/osrs-inferno/inventory.json`, sem payloads de assets versionados.
- Revisão independente de cache: offsets, endereçamentos e abertura somente leitura conferidos, sem problema bloqueante encontrado no diagnóstico.

## Resultado medido

| Região | Papel | Terreno origem | Localizações origem | Objetos posicionados | Tipos de objeto | Presença da região no cache Drynor |
| --- | --- | --- | --- | --- | --- | --- |
| 9042 / 35_82 | Arena remapeada | 8515 | 8516 | 2.850 | 69 | Ausente |
| 18258 / 71_82 | Região da entrada | 6987 | 6988 | 4.553 | 75 | Presente |

Arena: 38.992 bytes de terreno e 6.171 de localizações; todos os 16.384 tiles foram validados e não há bytes posteriores nesses dois payloads. Pisos brutos encontrados: overlays `72,152`; underlays `1,61,62,72,118,122,138,141,145,146`. Os IDs de piso ainda não foram convertidos para o catálogo Drynor.

Entrada: 38.165 bytes de tiles e 869 bytes posteriores no terreno, cujo ambiente ainda precisa ser interpretado. Não substituir a região existente automaticamente.

Foram encontradas 148 definições de objeto na união das duas regiões e dos objetos de pilares/saída explicitamente relacionados. As 20 definições candidatas de NPC da faixa `27690..27709` e o item `51295` estão presentes na origem. Presença não comprova que todos os candidatos sejam necessários, nem que sejam compatíveis.

Hashes SHA-256 dos payloads da arena:

- `m35_82`: `30ff1222accca017f4d7629cd213b313f6dd331ca5853b542631382d6e9ed13c`.
- `l35_82`: `f10f1f9df036901560c62632f06448a19baf5bcfc9ac41cbdbf4c5c894b1e457`.

## Caminhos de cache observados

`drynor-world-server/data/worldConfig.json` configura `../cache/`; o valor padrão de `Settings` também é esse. `drynor-core/gradle.properties` aponta a fonte de gamevals/build para `../cache-unpacked`. A camada de compatibilidade permite `CACHE_SOURCE_PATH`/`CACHE_BUILD_PATH` e usa `../cache-unpacked` como fallback; `DRYNOR_CACHE_BUILD=false` desliga seu build automático. Nenhuma dessas variáveis estava definida no ambiente deste diagnóstico.

O diagnóstico abre explicitamente `A:/dev/drynor/cache`, sem acionar o singleton ou o build automático. Não afirma qual cache um processo de jogo já aberto está usando. Antes de integrar a arena, conferir o caminho efetivo de startup e configurar saída empacotada isolada.

## Próximas pendências

1. Confirmar proveniência/permissão dos assets antes de copiá-los.
2. Inspecionar arquivos opcionais `um/ul` e confirmar sua necessidade para esta arena.
3. Implementar parser estrito de definições OSRS da origem, com falha explícita em opcode desconhecido.
4. Resolver modelos, animações, bases/frames, materiais/texturas, morphs, sons e demais dependências transitivas; incluir o pet e recursos referenciados somente pelo gameplay.
5. Planejar IDs determinísticos e pisos compatíveis com os limites do leitor 727.
6. Produzir a primeira arena convertida em saída isolada, ler o resultado e validar no cliente e no servidor.

Não houve alteração nos caches, no cliente ou na lógica do minigame nesta etapa. Não foi realizado teste visual no cliente. A Fase 0 continua em andamento; a lista de dependências ainda não está completa.

## Avanço posterior em 18/10/2026

O comando `plan-inferno` foi implementado e executado, com parsers estritos e uma proposta de IDs que não reserva nem escreve recursos. Foram encontrados 74 objetos da arena/pilares/morphs, 20 NPCs candidatos, 104 modelos, 22 sequências, 393 frames e 13 bases. Os 99 modelos com geometria passaram por regravação byte exata; cinco payloads são placeholders de 18 bytes zerados.

Os 28 testes de ferramentas OSRS passaram. Duas execuções do plano anterior ao perfil do novo cliente produziram SHA-256 idênticos; o novo plano continua ordenado e os testes verificam estabilidade da alocação. O relatório mais recente está em `build/osrs-inferno/dependency-plan-client-final.json`.

A nova referência do cliente alterou a decisão dos pisos: reutilizar os dez underlays nativos byte idênticos e propor os overlays brutos 72 e 152 nos arquivos de definição livres 248 e 249, correspondentes aos IDs de tile 249 e 250. A disponibilidade é uma fotografia deste cache; deve ser conferida de novo antes de escrever.

O suporte de paletas de underlay começou no cliente oficial `drynor-client-v2`, na mesma branch de desenvolvimento. Nenhuma paleta foi ativada e nenhum mapa foi importado. Esta observação posterior não substitui as limitações da primeira execução; veja `GIELENOR-CLIENT-STUDY.md` para as regras de runtime e pendências atuais.
