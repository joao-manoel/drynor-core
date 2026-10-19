# Adaptações do cliente Gielenor para conteúdo OSRS

Investigação de 18/10/2026. Referência somente leitura: `A:/dev/gielenor/gielenor-client`, commit `ebef1dc918e99fbf3ea6fca3b1d7e8c728406a8b`. Destino atual: `A:/dev/drynor/drynor-client-v2`, cliente oficial conforme o `AGENTS.md` vigente. A branch `codex/osrs-maps-inferno` foi criada também nesse novo repositório, a partir de seu `main`; a preparação anterior do cliente antigo continua sendo histórico, não uma validação do V2.

## Conclusão técnica

O suporte OSRS da Gielenor depende de adaptações em runtime além dos arquivos do cache. Copiar os mapas e obter um roundtrip de modelos correto não reproduz, sozinho, a aparência nem as animações desse cliente.

A estratégia continua sendo converter definições e recursos para os formatos 727 quando possível, materializando regras indispensáveis que a origem gera em memória. Alterações no cliente Drynor serão pequenas e baseadas nos consumidores efetivos. Não portar protocolo 718, UI, patches globais ou bugs da Gielenor para o Drynor 727.1.

## Terreno, instâncias e pisos

Evidências: `src/Settings.java:39–54`, `Class240.java:165–279,1383–1453`, `Class283.java:140–150`, `Class451.java:41–60`, `Class375.java:15–57`.

- O cliente identifica regiões OSRS pela lista de `Settings`, incluindo a arena remapeada 9042.
- Armazena pisos em arrays de `short`, aplica `+1000` e propaga o perfil da região original durante o carregamento de chunks instanciados.
- O loader de underlay retira esse offset na maioria dos casos, reutilizando a definição nativa. A exceção explícita é a definição 1026.
- O loader de overlay também aplica substituições e exceções. As duas usadas pelo Inferno, 1071 e 1151, permanecem OSRS.

**Descoberta medida:** os dez underlays efetivamente usados pelo cliente Gielenor na arena são byte a byte iguais aos do Drynor, nos mesmos arquivos: `0,60,61,71,117,121,137,140,144,145`. Logo, para reproduzir o visual da Gielenor neste primeiro mapa, não é necessário ocupar dez novos slots de underlay. Os dois overlays continuam exigindo conversão; há sete slots livres no destino observado.

Isso corrige a interpretação anterior baseada apenas nos pisos OSRS brutos: dez pisos brutos não significam dez definições novas para a política visual efetiva da Gielenor. O relatório mantém as duas visões separadas. Se a política futura for reproduzir os pisos OSRS originais, a capacidade de apenas três slots de underlay livres permanece relevante.

No V2, uma paleta por região de origem permite estender underlays sem mudar os bytes do terreno e sem substituir definições nativas. O armazenamento interno deve suportar IDs globais maiores, inclusive após rotação de instâncias. Overlays têm risco adicional: `FloType.slot` mistura prioridade e ID em oito bits; não ampliar seu namespace sem separar essa ordenação. O WorldMap tem um fluxo próprio.

## Modelos, texturas e materiais

Evidências: `Model.java:60–61,961–963,1616–1621`, `TextureLoader.java:139–140,191–195,3319–3329`, `TextureData.java:185–186`, `ObjectConfig.java:262–263`.

Os modelos OSRS têm IDs físicos deslocados em 200000. Materiais recebem um deslocamento intermediário de 2651, mas o resolvedor desfaz a maior parte dele:

| Material OSRS bruto | Material efetivo Gielenor |
| --- | --- |
| 10 | 8 |
| 7 | 42 |
| 59 | 2710, composição especial de fogo |
| Demais | ID bruto original |

Não importar indiscriminadamente todos os materiais para `raw+2651`. Pares de retexture também passam por essa resolução. IDs brutos, endereços físicos e recursos efetivos são conceitos distintos no manifesto.

**Infernal Cape:** a textura 2710 reutiliza a configuração e o payload da textura 40 e substitui o sprite 485 pelo sprite 20318. Essa composição pode não existir como recurso completo no cache de origem. Precisará ser materializada ou atendida por uma adaptação explícita, com dependências completas.

Dos 104 modelos levantados, 99 passam pelo codec 727 com regravação byte exata; cinco são arquivos de 18 bytes zerados, sem geometria. Isso comprova compatibilidade de representação desses 99 modelos com o codec testado, não equivalência de texturas, iluminação, posicionamento ou renderização no cliente.

## Animações e esqueletos

Evidências: `AnimationConfig.java:47–108`, `AnimationFrame.java:37–40,110–123`, `AnimationFrameBase.java:25–52`, `Class298_Sub37_Sub4.java:11–44`.

- Frames OSRS legados têm header de três bytes; o cliente normal usa quatro.
- Bases OSRS não carregam as colunas de sombra e submesh do formato 727; a origem usa sombra falsa e submesh `-1`.
- O cliente multiplica translações do tipo 1 por quatro e rotações dos tipos 2/9 por dezesseis antes da transformação normal de rotação. Inserir somente um byte no header produziria animações incorretas.
- Sequências resolvem arquivos de frames com deslocamento de 5000. Há modo forçado para sequências 26657/26658 e a partir de 28568, com namespace lógico adicional de 10000.
- Base zero é uma exceção: reutiliza a base nativa zero, salvo modo forçado. Não aplicar `+5000` universalmente.
- O cliente aceita tipo de transformação 6, remapeado para 2, e bits de tween nos flags. O probe inicial é deliberadamente mais restrito; rejeita extensões até terem tratamento comprovado.

O probe do cache real encontrou 22 sequências, 393 frames e 13 bases no perfil legado suportado, sem erro estrutural. Ainda é necessário converter os valores e calibrar a reprodução antes de considerar as animações prontas.

Alguns opcodes reconhecidos pelos scanners têm evidência apenas no servidor, não no cliente OSRS da Gielenor: NPC 42 e extensões de sequência acima de 13. A ferramenta não deve declarar esses recursos compatíveis com ambos só por conseguir lê-los.

## NPCs e stances

Evidências: `NPCConfig.java:107–112,662–750`, `Class349.java:56–87`, `Class507.java:19,35–37`, `CustomNPCs.java:466–469`.

Modelos e sequências recebem os deslocamentos conhecidos. As stances dos NPCs OSRS são sintetizadas em memória a partir de idle e walk; um NPC pode funcionar sem BAS correspondente no cache. No Drynor, gerar BAS explícita ou fornecer adaptação equivalente, sem tratar sua ausência na origem como recurso perdido.

O cliente desativa selectors de morph e força uma variante em determinados caminhos. Preservar as referências brutas no relatório e decidir o comportamento correto; não copiar esses atalhos silenciosamente. Não foi encontrado patch específico para o NPC 27706/Zuk nos loaders inspecionados.

## Objetos e ajustes de renderização

Evidências: `ObjectConfig.java:187,230–246,284–289,321–327,833–884`, `CustomObjects.java:152–159`, `Class433.java:163`.

- O cliente lê todos os modelos do opcode 1 corretamente. O bug que repetia o primeiro modelo existe no loader do servidor, não neste caminho do cliente.
- Offsets 70/71/72 já são multiplicados por quatro no leitor 727 do Drynor. Evitar multiplicar novamente na conversão.
- Objetos OSRS recebem ajustes por índice do modelo, deslocamento Y de -4 para shape 22 ou +1 nos demais, além de exceções. Essas regras precisam de política visual e comparação real; não são propriedades inferíveis do cache bruto.
- O objeto 10050 é transformado em “Zuk shield”, com modelo 233036, sequência 27567 e deslocamento Y+12. Porém não foi encontrada referência a esse objeto no controlador e nas classes do Inferno investigadas. O minigame usa o NPC 27707 como escudo; o override é uma dependência condicional, não prova de que o objeto deva ser importado. Modelo e sequência já apareceram por outras referências.

## Ambiente e limites de fidelidade

`Atmosphere.java:25–95` e `Class240_Sub1.java:203–205` fornecem ambiente padrão para regiões OSRS sem bloco próprio. A arena inspecionada não contém bytes de ambiente após os tiles; o visual depende também desse fallback. Verificar iluminação e neblina com os renderizadores do Drynor, especialmente Modern GL e Water Ultra.

## Ordem de implementação resultante

1. Fixar o perfil do cliente por commit e manter no relatório as dependências brutas e efetivas.
2. Para a primeira arena, reutilizar os dez underlays nativos comprovados e converter/remapear somente os dois overlays necessários.
3. Converter objetos e modelos, revisando transforms e materiais em vez de ativar todos os offsets da Gielenor no cliente.
4. Converter frames, bases e sequências com escala correta e gerar stances dos NPCs.
5. Materializar a composição visual da Infernal Cape e expandir efeitos e recursos referenciados pelo gameplay.
6. Empacotar em saída isolada, ler de volta e validar arena, instâncias, animações, iluminação e fronteiras no cliente atual.

Nenhum recurso do cache Gielenor foi copiado nesta investigação e nenhum fluxo visual do Inferno foi validado no cliente. O resultado é uma base de evidência para implementar a adaptação, não uma declaração de que o porte está pronto.

## Verificação da fundação implementada

Os 28 testes OSRS das ferramentas passaram com JDK 26. A tarefa `test` do cliente V2 passou com JDK 19 após a alteração de underlays para armazenamento interno maior e a propagação da região original nos caminhos normal e instanciado. A comparação signed usada pelo helper de rotação nativo foi preservada e ganhou regressão, evitando alterar a escolha de diagonais dos pisos existentes. Nenhuma paleta foi ativada; a validação visual de instâncias rotacionadas, shaders e mapas importados permanece pendente.
