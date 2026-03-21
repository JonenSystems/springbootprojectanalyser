/**
 * クラス図作成画面のJavaScript
 */
document.addEventListener('DOMContentLoaded', function() {
    // Mermaidの初期化
    mermaid.initialize({ 
        startOnLoad: false,
        theme: 'default',
        securityLevel: 'loose'
    });

    /**
     * 多行テキストは textarea / input のいずれでも取得できるようにする
     */
    function readDiagramSource(el) {
        if (!el) {
            return '';
        }
        var v = el.value;
        if (v === undefined || v === null) {
            v = el.textContent || '';
        }
        return String(v).trim();
    }

    // クラス図をレンダリング → 完了後にシーケンス図（同一ページで mermaid.run を直列化）
    const hiddenInput = document.getElementById('class-diagram-text');
    const mermaidElement = document.getElementById('mermaid-diagram');
    const sequenceHiddenInput = document.getElementById('sequence-diagram-text');
    const sequenceMermaidElement = document.getElementById('sequence-mermaid-diagram');

    var classPromise = Promise.resolve();
    var diagramText = readDiagramSource(hiddenInput);
    if (hiddenInput && mermaidElement && diagramText) {
        mermaidElement.textContent = diagramText;
        classPromise = Promise.resolve(mermaid.run({ nodes: [mermaidElement] })).catch(function(error) {
            console.error('Mermaid rendering error:', error);
            mermaidElement.innerHTML = '<p class="text-danger">クラス図の表示中にエラーが発生しました: ' + error.message + '</p>';
        });
    }

    classPromise.then(function() {
        var sequenceDiagramText = readDiagramSource(sequenceHiddenInput);
        if (!sequenceHiddenInput || !sequenceMermaidElement || !sequenceDiagramText) {
            return;
        }
        sequenceMermaidElement.textContent = sequenceDiagramText;
        return Promise.resolve(mermaid.run({ nodes: [sequenceMermaidElement] })).catch(function(error) {
            console.error('Mermaid rendering error (sequence):', error);
            sequenceMermaidElement.innerHTML = '<p class="text-danger">シーケンス図の表示中にエラーが発生しました: ' + error.message + '</p>';
        });
    });
    
    // ダウンロードボタンの処理（クラス図・シーケンス図を Mermaid 形式で1ファイルにまとめる）
    const downloadButton = document.getElementById('download-btn');
    if (downloadButton) {
        downloadButton.addEventListener('click', function() {
            const classDiagramText = hiddenInput ? readDiagramSource(hiddenInput) : '';
            const sequenceDiagramText = sequenceHiddenInput ? readDiagramSource(sequenceHiddenInput) : '';
            if (!classDiagramText && !sequenceDiagramText) {
                return;
            }
            var sections = [];
            if (classDiagramText) {
                sections.push('## クラス図\n\n```mermaid\n' + classDiagramText + '\n```\n');
            }
            if (sequenceDiagramText) {
                sections.push('\n## シーケンス図\n\n```mermaid\n' + sequenceDiagramText + '\n```\n');
            }
            var markdownText = sections.join('');
            var timestamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, -5);
            var fileName = 'mermaid-diagram-' + timestamp + '.md';

            var blob = new Blob([markdownText], { type: 'text/markdown' });
            var url = URL.createObjectURL(blob);

            var link = document.createElement('a');
            link.href = url;
            link.download = fileName;
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);

            URL.revokeObjectURL(url);
        });
    }
    
    // エンドポイント選択の保持
    const endpointSelect = document.getElementById('endpoint-select');
    const selectedEndpointIdInput = document.getElementById('selected-endpoint-id');
    const selectedEndpointUriInput = document.getElementById('selected-endpoint-uri');
    const selectedEndpointHttpMethodInput = document.getElementById('selected-endpoint-http-method');
    const selectedEndpointClassNameInput = document.getElementById('selected-endpoint-class-name');
    
    if (endpointSelect) {
        let selectedIndex = -1;
        
        // まず、エンドポイントIDで一致を試みる
        if (selectedEndpointIdInput && selectedEndpointIdInput.value) {
            const selectedId = selectedEndpointIdInput.value.trim();
            if (selectedId) {
                for (let i = 0; i < endpointSelect.options.length; i++) {
                    if (endpointSelect.options[i].value === selectedId) {
                        selectedIndex = i;
                        break;
                    }
                }
            }
        }
        
        // IDで一致しない場合、URI、HTTPメソッド、クラス名で一致を試みる
        if (selectedIndex === -1 && selectedEndpointUriInput && selectedEndpointHttpMethodInput && selectedEndpointClassNameInput) {
            const selectedUri = selectedEndpointUriInput.value.trim();
            const selectedHttpMethod = selectedEndpointHttpMethodInput.value.trim();
            const selectedClassName = selectedEndpointClassNameInput.value.trim();
            
            if (selectedUri && selectedHttpMethod && selectedClassName) {
                for (let i = 0; i < endpointSelect.options.length; i++) {
                    const optionText = endpointSelect.options[i].textContent;
                    // オプションテキストの形式: "/uri(HTTP_METHOD) : ClassName"
                    const match = optionText.match(/^(.+?)\((.+?)\)\s*:\s*(.+)$/);
                    if (match) {
                        const uri = match[1].trim();
                        const httpMethod = match[2].trim();
                        const className = match[3].trim();
                        
                        if (uri === selectedUri && httpMethod === selectedHttpMethod && className === selectedClassName) {
                            selectedIndex = i;
                            break;
                        }
                    }
                }
            }
        }
        
        // 一致するオプションが見つかった場合、選択する
        if (selectedIndex !== -1) {
            endpointSelect.selectedIndex = selectedIndex;
        }
    }
});

