# Quick smoke test: make ONE real LLM call to verify API key + model name work.
# Prints the raw HTTP response body so we can debug "Error while extracting response".

$ErrorActionPreference = "Stop"

$propDir = "target/llm-smoke-classes"
New-Item -ItemType Directory -Path $propDir -Force | Out-Null

$src = @'
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.chat.Message;
import com.alibaba.cloud.ai.dashscope.chat.RequestMessages;
import com.alibaba.cloud.ai.dashscope.chat.ResponseFormat;
import com.alibaba.cloud.ai.dashscope.chat.UserMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class LlmSmoke {
    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            // Fallback: parse application.yml
            for (String line : Files.readAllLines(Path.of("src/main/resources/application.yml"))) {
                if (line.trim().startsWith("api-key:")) {
                    String value = line.substring(line.indexOf(':') + 1).trim();
                    if (value.startsWith("${")) {
                        int colon = value.indexOf(":", 2);
                        int close = value.indexOf("}", colon);
                        if (colon > 0 && close > colon) {
                            apiKey = value.substring(colon + 1, close).trim();
                        }
                    } else {
                        apiKey = value;
                    }
                    break;
                }
            }
        }
        System.out.println("[SMOKE] apiKey loaded: " + (apiKey != null && !apiKey.isBlank()));
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[SMOKE] no api key");
            System.exit(2);
        }
        String modelName = args.length > 0 ? args[0] : "qwen3.7-flash";
        System.out.println("[SMOKE] using model: " + modelName);

        DashScopeApi api = DashScopeApi.builder()
                .apiKey(apiKey)
                .build();
        DashScopeChatOptions opts = DashScopeChatOptions.builder()
                .model(modelName)
                .withTemperature(0.0)
                .withMultiModel(true)
                .build();

        DashScopeChatModel chatModel = DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(opts)
                .build();

        try {
            var resp = chatModel.call(new org.springframework.ai.chat.prompt.Prompt(
                    List.of(
                            new org.springframework.ai.chat.messages.SystemMessage("你是一个测试 agent。"),
                            new org.springframework.ai.chat.messages.UserMessage("回复 'ok' 两个字符")
                    ),
                    opts
            ));
            System.out.println("[SMOKE] call ok, response: " + resp);
        } catch (Throwable t) {
            System.err.println("[SMOKE] call FAILED: " + t.getClass().getName() + ": " + t.getMessage());
            Throwable cause = t.getCause();
            while (cause != null) {
                System.err.println("[SMOKE] caused by: " + cause.getClass().getName() + ": " + cause.getMessage());
                cause = cause.getCause();
            }
            System.exit(1);
        }
    }
}
'@

[System.IO.File]::WriteAllText("$propDir/LlmSmoke.java", $src, [System.Text.UTF8Encoding]::new($false))

Write-Host "Compiling smoke test..."
& javac -d $propDir -cp "target/classes;$(Get-ChildItem target/dependency-jars/*.jar 2>$null -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName)" "$propDir/LlmSmoke.java" 2>&1 | Out-Null

if ($LASTEXITCODE -ne 0) {
    # try mvn to get classpath
    $cp = mvn dependency:build-classpath -q -DincludeScope=test -Dmdep.outputFile=/tmp/cp.txt 2>&1 | Out-Null
    $cpContent = Get-Content /tmp/cp.txt -ErrorAction SilentlyContinue
    if ($cpContent) {
        Write-Host "Compiling with mvn classpath..."
        & javac -d $propDir -cp "target/classes;$cpContent" "$propDir/LlmSmoke.java"
    }
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Manual compile failed; falling back to mvn exec:java"
        & mvn -q exec:java -Dexec.mainClass="dummy" -Dexec.classpathScope=test 2>&1 | Out-Null
        Write-Host "Use: mvn exec:java -Dexec.mainClass=LlmSmoke -Dexec.classpathScope=test -Dexec.args=qwen3.7-flash"
        return
    }
}

Write-Host "Running smoke..."
& java -cp "$propDir;target/classes" LlmSmoke qwen3.7-flash