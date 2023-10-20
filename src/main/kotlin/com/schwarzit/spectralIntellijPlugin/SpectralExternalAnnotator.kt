package com.schwarzit.spectralIntellijPlugin

import com.intellij.json.psi.JsonFile
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.schwarzit.spectralIntellijPlugin.settings.ProjectSettingsState
import org.jetbrains.yaml.psi.YAMLFile
import org.springframework.util.AntPathMatcher
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

class SpectralExternalAnnotator : ExternalAnnotator<Pair<PsiFile, Editor>, List<SpectralIssue>>() {
    companion object {
        val logger = getLogger()
    }

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Pair<PsiFile, Editor>? {
        if (file !is JsonFile && file !is YAMLFile) return null

        val settings = editor.project?.service<ProjectSettingsState>()
        val includedFiles = settings?.includedFiles?.lines() ?: emptyList()

        try {
            if (!isFileIncluded(
                    Paths.get(file.project.basePath ?: "/"),
                    file.virtualFile.toNioPath(),
                    includedFiles
                )
            ) {
                logger.trace("The given file ${file.virtualFile.toNioPath()} did not match any pattern defined in the settings")
                return null
            }
        } catch (e: Throwable) {
            logger.error("Failed to check if current file is included. Parameters: basePath: ${file.project.basePath}, path: ${file.virtualFile.toNioPath()}, includedFiles: $includedFiles")
            return null
        }

        return Pair(file, editor)
    }

    fun isFileIncluded(basePath: Path, path: Path, includedFiles: List<String>, separator: String = File.separator): Boolean {
        val pathMatcher = AntPathMatcher(separator)
        val finalPath = normalizedStringPath(path.toString(), separator);

        return includedFiles.any { s ->
            if (s.isEmpty()) return false;

            var finalGlobPattern = normalizedStringPath(s, separator);

            if (pathStartWithPattern(finalGlobPattern) || !Paths.get(finalGlobPattern).isAbsolute) {
                var base = normalizedStringPath(basePath.toString(), separator);
                if (!base.endsWith(separator)) base += separator
                finalGlobPattern = base + finalGlobPattern
            }

            return pathMatcher.match(finalGlobPattern, finalPath)
        }
    }

    private fun pathStartWithPattern(path: String): Boolean {
        return path.isNotEmpty() && path[0] == '*';
    }

    private fun normalizedStringPath(path: String, separator: String): String {
        return path.replace('\\', separator[0]).replace('/', separator[0]);
    }

    override fun doAnnotate(info: Pair<PsiFile, Editor>): List<SpectralIssue> {
        val progressManager = ProgressManager.getInstance()
        val computable = Computable { lintFile(info.second) }
        val indicator = BackgroundableProcessIndicator(
            info.second.project,
            "Spectral: analyzing OpenAPI specification '${info.first.name}' ...",
            "Stop",
            "Stop file analysis",
            false
        )

        return progressManager.runProcess(computable, indicator)
    }

    private fun lintFile(editor: Editor): List<SpectralIssue> {
        val project = editor.project
        if (project == null) {
            logger.error("Unable to lint file, editor.project was null")
            return emptyList()
        }

        val linter = project.service<SpectralRunner>()

        return try {
            val issues = linter.run(editor.document.text)
            issues
        } catch (e: Throwable) {
            logger.error(e)
            emptyList()
        }
    }

    override fun apply(file: PsiFile, issues: List<SpectralIssue>?, holder: AnnotationHolder) {
        val documentManager = PsiDocumentManager.getInstance(file.project)
        val document = documentManager.getDocument(file) ?: return

        if (issues == null) return

        if (issues.any { issue -> issue.code == "unrecognized-format" }) {
            logger.warn("Linted openapi spec is not valid: Skipping linting")
            holder.newAnnotation(HighlightSeverity.WARNING, "File is not formatted correctly. Linting was skipped.")
                .fileLevel()
                .create()
            return
        }

        for (issue in issues) {
            // It happens that spectral produces invalid text ranges, those will just be ignored
            var textRange: TextRange
            try {
                textRange = calculateIssueTextRange(document, issue.range)
            } catch (e: Throwable) {
                continue
            }

            holder
                .newAnnotation(
                    mapSeverity(issue.severity),
                    issue.code + ": " + issue.message
                )
                .range(textRange)
                .create()
        }
    }

    private fun calculateIssueTextRange(document: Document, range: ErrorRange): TextRange {
        val startOffset = document.getLineStartOffset(range.start.line) + range.start.character
        val endOffset = document.getLineStartOffset(range.end.line) + range.end.character

        return TextRange(startOffset, endOffset)
    }
}
