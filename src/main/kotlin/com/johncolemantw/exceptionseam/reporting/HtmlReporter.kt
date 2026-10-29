package com.johncolemantw.exceptionseam.reporting

import com.johncolemantw.exceptionseam.model.AnalysisResult
import com.johncolemantw.exceptionseam.model.ExceptionOrigin
import com.johncolemantw.exceptionseam.model.Issue
import com.johncolemantw.exceptionseam.model.IssueKind
import com.johncolemantw.exceptionseam.model.LikelyBugs
import com.johncolemantw.exceptionseam.model.SeamResult
import com.johncolemantw.exceptionseam.model.Severity
import java.io.File

/** Self-contained HTML report: filterable table, source context and reachable-exception details. */
class HtmlReporter(
    private val outputFile: File,
    /** Directory that issue file paths are relative to; used to show code context. */
    private val baseDir: File,
) : Reporter {

    override fun report(result: AnalysisResult) {
        val findings = result.results.flatMap { r -> r.issues.map { r to it } }
            .sortedWith(compareByDescending<Pair<SeamResult, Issue>> { it.second.severity }
                .thenBy { it.second.file }.thenBy { it.second.line })
        val html = buildString {
            append(HEAD)
            appendLine("<h1>Exception Seam Report</h1>")
            appendLine("<p class=\"sub\">${result.results.size} catch seams analysed &middot; " +
                "${result.issues.size} issue(s) &middot; ${result.allowListed.size} allow-listed</p>")
            appendLine("<div class=\"summary\">")
            result.countBySeverity.forEach { (s, n) ->
                appendLine("<span class=\"badge ${s.name.lowercase()}\">$n ${s.name}</span>")
            }
            appendLine("</div>")
            appendLine("<div class=\"controls\">")
            appendLine("<select id=\"fSeverity\"><option value=\"\">All severities</option>" +
                Severity.entries.reversed().joinToString("") { "<option>${it.name}</option>" } + "</select>")
            appendLine("<select id=\"fKind\"><option value=\"\">All kinds</option>" +
                IssueKind.entries.joinToString("") { "<option>${it.name}</option>" } + "</select>")
            appendLine("<input id=\"fText\" type=\"search\" placeholder=\"Filter by file, method, exception…\">")
            appendLine("<label><input id=\"fAllow\" type=\"checkbox\"> show allow-listed</label>")
            appendLine("<span id=\"count\"></span>")
            appendLine("</div>")
            appendLine("<table><thead><tr><th>Severity</th><th>Kind</th><th>Location</th><th>Issue</th></tr></thead><tbody>")
            val allowListed = result.results.flatMap { r -> r.issues.filter { it.allowListedBy != null }.map { r to it } }
            (findings + allowListed).forEach { (r, i) -> appendRows(r, i) }
            appendLine("</tbody></table>")
            if (findings.isEmpty() && allowListed.isEmpty()) appendLine("<p>No issues found.</p>")
            append(SCRIPT)
        }
        outputFile.parentFile.mkdirs()
        outputFile.writeText(html)
    }

    private fun StringBuilder.appendRows(r: SeamResult, i: Issue) {
        val search = listOf(i.file, i.enclosingMethod.orEmpty(), i.message, i.caughtTypes.joinToString(" "),
            i.reachable.joinToString(" ")).joinToString(" ").lowercase()
        val allow = i.allowListedBy != null
        appendLine("<tr class=\"issue${if (allow) " allow" else ""}\" data-severity=\"${i.severity}\" " +
            "data-kind=\"${i.kind}\" data-allow=\"$allow\" data-text=\"${esc(search)}\">")
        appendLine("<td><span class=\"badge ${i.severity.name.lowercase()}\">${i.severity}</span></td>")
        appendLine("<td>${i.kind}</td>")
        appendLine("<td><code>${esc(i.file)}:${i.line}</code>" +
            (i.enclosingMethod?.let { "<br><small>${esc(it)}</small>" } ?: "") + "</td>")
        appendLine("<td>${esc(i.message)}${if (allow) " <em>(allow-listed by ${esc(i.allowListedBy!!)})</em>" else ""}</td>")
        appendLine("</tr>")
        appendLine("<tr class=\"detail\" hidden><td colspan=\"4\">")
        appendLine("<p><strong>Suggestion:</strong> ${esc(i.suggestion)}</p>")
        if (i.likelyBugs.isNotEmpty()) {
            appendLine("<p><strong>Likely bugs:</strong> ${esc(LikelyBugs.describe(i.likelyBugs))} - fix, or let them fail; do not swallow</p>")
        }
        i.suggestedCatch?.let { appendLine("<p><strong>Suggested catch:</strong> <code class=\"suggest\">${esc(it)}</code></p>") }
        codeContext(r)?.let { appendLine(it) }
        appendLine(reachableTable(r))
        appendLine("</td></tr>")
    }

    private fun codeContext(r: SeamResult): String? {
        val lines = runCatching { baseDir.resolve(r.seam.file).readLines() }.getOrNull() ?: return null
        val from = maxOf(1, r.seam.line - 3)
        val to = minOf(lines.size, r.seam.endLine + 2)
        if (from > to) return null
        return buildString {
            append("<pre class=\"code\">")
            for (n in from..to) {
                val hl = n in r.seam.line..r.seam.endLine
                append("<span class=\"ln${if (hl) " hl" else ""}\">${n.toString().padStart(4)}  ${esc(lines[n - 1])}</span>\n")
            }
            append("</pre>")
        }
    }

    private fun reachableTable(r: SeamResult): String {
        if (r.reachable.isEmpty()) return "<p><em>No reachable exceptions found.</em></p>"
        return buildString {
            append("<table class=\"inner\"><thead><tr><th>Reachable exception</th><th>Origin</th><th>Checked</th>" +
                "<th>Via</th><th>Notes</th></tr></thead><tbody>")
            r.reachable.forEach { e ->
                val notes = listOfNotNull(
                    e.reason,
                    e.handledBy?.let { "handled by $it" },
                    "also observed at runtime".takeIf { e.observed && e.origin != ExceptionOrigin.RUNTIME_OBSERVED },
                ).joinToString("; ")
                append("<tr><td><code>${esc(e.type)}</code></td><td>${e.origin}</td>" +
                    "<td>${e.checked ?: "?"}</td>" +
                    "<td>${if (e.callPath.isEmpty()) "try body" else esc(e.callPath.joinToString(" → "))}</td>" +
                    "<td>${esc(notes)}</td></tr>")
            }
            append("</tbody></table>")
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private companion object {
        val HEAD = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Exception Seam Report</title>
<style>
:root{--bg:#fff;--fg:#1b1f24;--muted:#656d76;--line:#d0d7de;--card:#f6f8fa;--crit:#d1242f;--high:#d9620b;--med:#b58900;--low:#8b5a2b;--hl:#fff8c5}
@media (prefers-color-scheme:dark){:root{--bg:#0d1117;--fg:#e6edf3;--muted:#8d96a0;--line:#30363d;--card:#161b22;--crit:#ff6a69;--high:#ff9f43;--med:#f5d90a;--low:#c08552;--hl:#3a3100}}
body{font:14px/1.5 system-ui,sans-serif;background:var(--bg);color:var(--fg);margin:0;padding:24px 16px;max-width:1100px;margin-inline:auto}
h1{margin:0 0 4px}.sub{color:var(--muted);margin:0 0 12px}
.summary,.controls{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin:12px 0}
.badge{border:1px solid currentColor;border-radius:12px;padding:1px 8px;font-size:12px;font-weight:600}
.critical{color:var(--crit);border-width:2px}.high{color:var(--high)}.medium{color:var(--med)}.low{color:var(--low)}
select,input[type=search]{background:var(--card);color:var(--fg);border:1px solid var(--line);border-radius:6px;padding:5px 8px}
input[type=search]{flex:1;min-width:200px}
table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line);vertical-align:top}
tr.issue{cursor:pointer}tr.issue:hover{background:var(--card)}tr.allow{opacity:.6}
tr.detail>td{background:var(--card)}table.inner{margin-top:8px}
pre.code{background:var(--bg);border:1px solid var(--line);border-radius:6px;padding:8px;overflow-x:auto;margin:8px 0}
.ln{display:block}.hl{background:var(--hl)}code{font-family:ui-monospace,monospace;font-size:12.5px}
@media (max-width:640px){th:nth-child(2),td:nth-child(2){display:none}}
</style></head><body>
"""
        val SCRIPT = """<script>
(function(){
  var rows=[].slice.call(document.querySelectorAll('tr.issue'));
  var sev=document.getElementById('fSeverity'),kind=document.getElementById('fKind'),
      txt=document.getElementById('fText'),allow=document.getElementById('fAllow'),count=document.getElementById('count');
  function apply(){
    var shown=0,q=txt.value.toLowerCase();
    rows.forEach(function(r){
      var ok=(!sev.value||r.dataset.severity===sev.value)&&(!kind.value||r.dataset.kind===kind.value)&&
             (!q||r.dataset.text.indexOf(q)>-1)&&(allow.checked||r.dataset.allow!=='true');
      r.hidden=!ok; r.nextElementSibling.hidden=!ok||!r.classList.contains('open'); if(ok)shown++;
    });
    count.textContent=shown+' shown';
  }
  rows.forEach(function(r){r.addEventListener('click',function(){
    r.classList.toggle('open'); r.nextElementSibling.hidden=!r.classList.contains('open');
  });});
  [sev,kind,txt,allow].forEach(function(e){e.addEventListener('input',apply);e.addEventListener('change',apply);});
  apply();
})();
</script></body></html>
"""
    }
}
