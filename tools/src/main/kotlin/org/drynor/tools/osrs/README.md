# Diagnóstico inicial de conteúdo OSRS

O comando `osrs inventory-inferno` inspeciona o cache híbrido da Gielenor sem abrir nenhum dos caches para escrita. Não importa recursos, não aloca IDs e não modifica o cliente ou o world.

Exemplo, a partir de `drynor-core`, com JDK 26 configurado:

```powershell
.\gradlew.bat :tools:run --args="osrs inventory-inferno A:/dev/gielenor/gielenor-source/data/cache A:/dev/drynor/cache A:/dev/drynor/drynor-core/build/osrs-inferno/inventory.json"
```

O relatório deve ficar fora dos dois caches e não pode existir previamente. Para uma nova execução, escolher outro arquivo. Os caminhos de entrada são explícitos: o comando não chama `Cache.get()` nem aciona o build automático de cache.

## Evidência da origem

- `Inferno.java`: instância em chunks `(280,656)`, arena Gielenor `35_82`, saída em `(4571,5257)`, região `71_82`.
- `PackOSRSMap.java`: referência à arena OSRS `9043`, remapeada para `9042`; paisagens são gravadas sem XTEA após leitura da origem com chave.
- `Region.java` e `Settings.java`: objetos de regiões OSRS recebem `+100000` durante o carregamento; esse deslocamento não está aplicado nos IDs do arquivo de localizações. A arena está na lista OSRS; a região da entrada não está.
- `PackOSRSData.java`: definições de objetos/itens usam grupos de 256 arquivos; NPCs usam grupos de 128. IDs foram deslocados, mas as definições OSRS continuam com sua gramática própria.

Os IDs de NPCs são candidatos extraídos da faixa observada no gameplay, não uma afirmação de que todos sejam usados. Pilares são objetos criados em runtime e entram separadamente no inventário. O pet e dependências transitivas ainda precisam de levantamento próprio.

## Limites

O relatório registra presença, hashes de payloads, ocupação de IDs no destino, contagens de objetos e pisos brutos. Valida todos os terminadores e payloads de terreno antes de usar o decoder 727, que tolera EOF. Bytes posteriores de ambiente são contabilizados, sem interpretação.

Definições OSRS não são passadas aos decoders 727: modelos, animações, texturas, morphs e sons ainda não são resolvidos. Erros de leitura de arquivos são mantidos no relatório, sem substituição por mapas vazios. A conclusão normal do comando significa que o relatório foi produzido, não que todos os recursos são compatíveis ou sequer legíveis.

Próxima etapa: parser estrito de definições OSRS da revisão efetivamente encontrada, fechamento de dependências, mapeamento de pisos e plano determinístico de importação para uma saída isolada. Não aplicar simplesmente o offset `+1000` aos pisos do terreno: o leitor 727 utiliza campos de tamanho limitado.

## Plano inicial de dependências e IDs

`osrs plan-inferno <origem-empacotada> <destino-empacotado> <relatorio-novo.json>` expande as definições da arena e dos NPCs candidatos, seguindo morphs, modelos, sequências, frames e bases do perfil legado. Mantém erros e pendências explícitos; a saída continua `blocked-for-import` enquanto a conversão e as dependências restantes não estiverem resolvidas.

O planejador preserva IDs de origem livres no destino antes de realocar conflitos, ordena as entradas e detecta overflow. As propostas não reservam IDs, não implicam reutilização de conteúdo por coincidência de número e ainda exigem validação das larguras dos campos de cada consumidor. Uma nova importação deverá validar o manifesto e os hashes novamente contra o cache escolhido.

O relatório registra hashes das reference tables do índice 255 e de cada payload inspecionado; o comando confere a estabilidade das reference tables antes/depois. Não se trata de um digest integral do cache nem de uma transação atômica contra alterações externas.

O perfil real do cliente Gielenor está fixado por commit. Pisos brutos e efetivos, aliases de materiais, stances sintetizadas e composição da capa têm regras diferentes de uma importação puramente binária. Consulte `GIELENOR-CLIENT-STUDY.md` antes de gerar assets. Os parsers são de dependências, não conversores completos de definições; runtime de cliente e servidor pode ter gramáticas diferentes.

## Prévia estática isolada

```powershell
.\gradlew.bat :tools:run --args="osrs preview-inferno A:/dev/gielenor/gielenor-source/data/cache A:/dev/drynor/cache A:/dev/drynor/drynor-core/build/osrs-inferno/static-preview-v1"
```

O último argumento deve ser um diretório novo, fora dos dois caches. A ferramenta converte definições e modelos usados pela arena, gera uma representação intermediária JSON/GLB, recompila esses arquivos pelos codecs atuais e grava o resultado numa cópia nova do cache empacotado. O cache original é aberto somente para leitura; seus arquivos são conferidos por SHA-256 antes/depois.

Saídas:

- `packed-cache/`: cache completo de desenvolvimento com a arena estática adicionada.
- `source-package/`: representação intermediária dos recursos convertidos, com metadados por archive. Não é uma SourceTree completa. O comando atual regenera essa representação da origem; ainda não oferece reconstrução a partir de edições manuais posteriores.
- `preview-report.json`: contagens, omissões, placeholders normalizados e verificações.
- `dependency-plan.json`: plano usado no experimento.

A ferramenta recusa sobrescrever recursos existentes. Depois de gravar, reabre a cópia, compara os recursos novos, os arquivos antigos dos grupos modificados e os containers de todos os demais archives. Exige IDs posicionados compatíveis com o armazenamento de 17 bits de MapObject e pisos dentro dos limites do formato 727.

Esta prévia desativa animações, sons, mapscene/map icons e omite placements dependentes de morphs não resolvidos. Não aplica correções visuais de runtime por shape nem ambiente específico e não inclui NPCs ou gameplay. As omissões estão no relatório; não apontar o servidor normal para esse cache como se fosse o Inferno completo.

Execução verificada em 18/10/2026: 73 definições de objeto, 80 modelos e 2.849 de 2.850 placements; um placement de morph omitido. Foram conferidos 141.260 archives inalterados, os arquivos nativos do grupo de overlays e os hashes do cache original. A suíte core passou com 406 testes e a suíte tools com 37 testes. Renderização no cliente e caminhada/colisão em jogo continuam pendentes.

## NPCs e animações

`osrs audit-actors-inferno <origem> <baseline> <diretorio-novo>` converte os candidatos reais para arquivos binários de diagnóstico, relê pelos decoders nativos e registra cada falha em `actor-audit.json`. Saída parcial não é um cache instalável. Em 18/10/2026 passaram 20 NPCs, 22 sequências, 393 frames e 13 bases, sem bloqueios nesse conjunto. Isso não inclui todas as animações usadas exclusivamente pelo combate.

`osrs preview-actors-inferno <origem> <baseline> <diretorio-novo>` gera novamente a arena isolada, executa a auditoria e exige ausência de bloqueios. Gera a representação editável dos novos assets, recompila e confere bytes antes de adicionar NPCs, BAS, sequências, frames, bases e modelos restantes à cópia em `arena/packed-cache`. A representação `actor-source-package` é intermediária, não uma SourceTree completa nem um comando de reconstrução de edições manuais. O relatório final é `actor-preview-report.json`.

As translações dos frames são multiplicadas por quatro, as rotações são convertidas com a periodicidade do leitor 727 e BAS explícitos substituem stances sintetizadas pela Gielenor. A prévia descarta sons/equipamentos das sequências e ícones de NPC; customizações Java de NPC não são importadas automaticamente. Morphs, formatos desconhecidos, materiais compostos e referências não resolvidas são recusados. O cache disponibiliza assets para testes, sem registrar ondas, combate, efeitos, recompensas ou spawns automáticos.

Execução verificada: `build/osrs-inferno/actor-preview-v2/actor-preview-report.json`, 492 assets adicionais e 141.259 archives originais conferidos. A prévia totaliza os 104 modelos do plano. Os grupos de frames editáveis usam três seções (header, flags e valores), exigidas pelo codec GLB; um grupo genérico de uma seção gera fallback e é recusado antes da gravação. Os 49 testes tools passaram. Renderização e movimento no cliente continuam pendentes.

O world passou em `verifyNativeWorldModelCache -PcachePath=A:/dev/drynor/drynor-core/build/osrs-inferno/actor-preview-v2/arena/packed-cache/`: 27.669 NPCs, 130.349 objetos e 27.537 animações, sem diferenças nos campos comparados. Esse probe lê o cache e compara os consumidores, sem iniciar uma sessão de jogo. Log local em `drynor-world-server/.local-runtime/osrs-inferno-actor-cache-probe.log`.

## Infernal cape — 19/10/2026

`osrs reward-inferno <gielenor-packed-cache> <preview-anterior/arena/packed-cache> <novo-preview>` copia a prévia existente e importa somente os recursos da capa. A origem permanece somente leitura. O destino deve estar fora da prévia anterior e não existir. Exemplo executado:

```powershell
./gradlew.bat :tools:run '--args=osrs reward-inferno A:/dev/gielenor/gielenor-source/data/cache A:/dev/drynor/drynor-core/build/osrs-inferno/port-preview-v3/arena/packed-cache A:/dev/drynor/drynor-core/build/osrs-inferno/reward-preview-v2'
```

O item físico da origem 51295 usa a gramática OSRS (`u16` para modelos, mais 200000), diferente do `bigSmart` nativo. A importação aloca o primeiro ID fisicamente livre entre 30000 e 32766; nesta execução, **30000**, em index19/archive117/file48. Reutilizar 51295 seria incorreto para a aparência equipada, que codifica `ushort(16384 + id)`. Modelos: inventário233144, masculino233103 e feminino233111. Slot1, opção Wear, não empilhável e não negociável. Bônus da customização Java da Gielenor: ataques4/4/4/1/1, defesas12/12/12/12/12, força8 (`param641=80`) e prayer2; sem requisitos de skill inventados. O link placeholder148→51296 é registrado no relatório e omitido, pois o placeholder não foi importado.

A textura composta da Gielenor não é descartada: seu client clona material40 para OSRS+59, usa a operação de textura40 e substitui sprite485 por20318. O pacote materializa essa operação no material/textura nativo2594 e copia o sprite20318, com `textureSpeedU=0`, `textureSpeedV=-10`; todos os demais campos de material permanecem os do doador. O material40 da origem e do destino foram comparados e são iguais. Partículas, billboards, materiais inesperados, offsets de equipamento não nulos e opcodes desconhecidos são recusados explicitamente.

`inferno-reward-report.json` identifica item, modelos, material, sprite, links omitidos e os binários `reward-binary/<index>/<archive>/<file>.bin` para os probes do client. `reward-source-package` guarda JSON, GLB e PNG editáveis com recompilação byte a byte conferida, incluindo o blob completo de materiais. É uma representação intermediária, não uma SourceTree completa. Foram conferidos todos os novos arquivos no cache final, **141432 archives anteriores inalterados** e **2594 materiais anteriores preservados**; source/baseline continuam intactos. **56 testes tools passaram**, incluindo limites de smart, CP1252, signedness, placeholder explícito e permissão de substituição do blob. Aparência equipada/ícone e animação da textura ainda precisam de confirmação visual no client. A lógica de concessão pertence ao world; este comando não concede recompensas.

O carregamento nativo de itens também foi corrigido para dimensionar archives esparsos pelo último file ID, em vez da quantidade de arquivos. A regressão carregou o item30000 (archive117/file48) com nome e slot, e a leitura do cache real retornou30001 itens com a capa acessível. A suíte core completa passou com409 testes. O trailer de10 bytes da textura é preservado no manifest como textureTrailerHex; o client727 encerra a leitura após os três outputs e não interpreta esse residual.
