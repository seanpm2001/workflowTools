package com.vmware.scm;

import com.vmware.scm.diff.PendingChangelistToGitDiffCreator;
import com.vmware.util.MatcherUtils;
import com.vmware.util.StringUtils;
import com.vmware.util.input.InputUtils;
import com.vmware.util.logging.LogLevel;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.vmware.scm.FileChangeType.deletedAfterRename;
import static com.vmware.scm.FileChangeType.renamed;
import static com.vmware.util.StringUtils.appendWithDelimiter;
import static java.lang.String.format;

/**
 * Wrapper around p4 commands
 */
public class Perforce extends BaseScmWrapper {

    private static final Pattern whereDepotFileInfoPattern = Pattern.compile("(//.+?)\\s+");
    private static final Pattern whereLocalFileInfoPattern = Pattern.compile(".+?\\s+.+?\\s+(.+)");

    private String clientName;

    public Perforce(String username, String clientName, String clientDirectory) {
        super(ScmType.perforce);
        if (StringUtils.isNotBlank(clientName) && StringUtils.isNotBlank(clientDirectory)) {
            this.clientName = clientName;
            super.setWorkingDirectory(new File(clientDirectory));
        } else if (StringUtils.isNotBlank(clientName)) {
            this.clientName = clientName;
            super.setWorkingDirectory(determineClientDirectoryForClientName());
        } else if (StringUtils.isNotBlank(clientDirectory)) {
            super.setWorkingDirectory(new File(clientDirectory));
            this.clientName = determineClientNameForDirectory(username);
        } else {
            throw new IllegalArgumentException("one of perforceClientName or perforceClientDirectory config values must be set, can also set client name by git-p4.client git config value");
        }
    }

    public List<String> getPendingChangelists() {
        return getPendingChangelists(false);
    }

    private List<String> getPendingChangelists(boolean includeSummary) {
        String changeListText = executeScmCommand("changes -c {} -s pending", clientName);
        if (StringUtils.isBlank(changeListText)) {
            return Collections.emptyList();
        }
        List<String> changeLists = new ArrayList<>();
        for (String line : changeListText.split("\n")) {
            String changelist = MatcherUtils.singleMatch(line, "Change\\s+(\\d+)\\s+on");
            if (includeSummary) {
                changelist += " " + MatcherUtils.singleMatch(line, "\\s+'(.+?)'");
            }
            changeLists.add(changelist);
        }
        return changeLists;
    }

    public String selectPendingChangelist() {
        List<String> changelistIds = getPendingChangelists(true);
        if (changelistIds.isEmpty()) {
            throw new RuntimeException("No pending change lists in client " + clientName + " to select from");
        }
        if (changelistIds.size() == 1) {
            return changelistIds.get(0);
        }
        int selectedIndex = InputUtils.readSelection(changelistIds, "Select changelist");
        return changelistIds.get(selectedIndex);
    }

    public String readChangelist(String changelistId) {
        String output = executeScmCommand("describe -s {}", changelistId);
        output = output.replaceAll("\n\t", "\n");
        return output;
    }

    public String printToFile(String fileToPrint, File outputFile) {
        return executeScmCommand("print -o {} {}", outputFile.getPath(), fileToPrint);
    }

    public void deletePendingChangelist(String changelistId) {
        executeScmCommand("change -d " + changelistId, LogLevel.INFO);
    }

    public void revertChangesInPendingChangelist(String changelistId) {
        executeScmCommand("revert -w -c {} //...", LogLevel.INFO, changelistId);
    }

    public void revertFiles(String changelistId, List<String> filePaths) {
        executeScmCommand("revert -w -c {} {}", changelistId, appendWithDelimiter("", filePaths, " "));
    }

    public void revertFiles(List<String> filePaths) {
        executeScmCommand("revert -w {}", appendWithDelimiter("", filePaths, " "));
    }

    public String createPendingChangelist(String description, boolean filesExpected) {
        String perforceTemplate = executeScmCommand("change -o");
        String amendedTemplate = updateTemplateWithDescription(perforceTemplate, description, filesExpected);
        String output = executeScmCommand("change -i", amendedTemplate, LogLevel.DEBUG);
        return changeSucceeded(output) ? MatcherUtils.singleMatch(output, "Change\\s+(\\d+)\\s+created") : null;
    }

    public void clean() {
        executeScmCommand("clean //...", LogLevel.INFO);
    }

    public void moveAllOpenFilesToChangelist(String changelistId) {
        executeScmCommand("reopen -c " + changelistId + " //...", LogLevel.INFO);
    }

    public String moveFilesToChangelist(String changelistId, List<String> filePaths) {
        String output = executeScmCommand("reopen -c {} {}", changelistId, appendWithDelimiter("", filePaths, " "));
        exitIfExpectedTextNotPresent(output, "reopened; change " + changelistId, filePaths.size());
        return output;
    }

    public String getFileInfo(String filePath) {
        return executeScmCommand("files " + filePath);
    }

    public Map<String, String> getWhereDepotFileInfoForRelativePaths(List<String> filePaths) {
        String fullPathPrefix = getWorkingDirectory().getPath() + File.separator;
        String filePathTexts = fullPathPrefix + appendWithDelimiter("", filePaths, " " + fullPathPrefix);
        String[] whereFileOutput = executeScmCommand("where " + filePathTexts).split("\n");
        return addMatchedValuesToMap(filePaths, Arrays.asList(whereFileOutput), whereDepotFileInfoPattern);
    }

    public Map<String, String> getWhereLocalFileInfo(List<String> filePaths) {
        String filePathTexts = appendWithDelimiter("", filePaths, " ");
        String[] whereFileOutput = executeScmCommand("where " + filePathTexts).split("\n");
        return addMatchedValuesToMap(filePaths, Arrays.asList(whereFileOutput), whereLocalFileInfoPattern);
    }

    public String fstat(List<String> fileNames) {
        return executeScmCommand("fstat {}", StringUtils.appendWithDelimiter("", fileNames, " "));
    }

    public List<String> getOpenedFilesInClient() {
        return parseFileNamesFromOpenedOutput(executeScmCommand("opened"));
    }

    public List<String> getOpenedFilesInChangelist(String changelistId) {
        return parseFileNamesFromOpenedOutput(executeScmCommand("opened -c {}", changelistId));
    }

    public Map<String, List<FileChange>> getAllFileChangesInClient() {
        List<String> fileNames = getOpenedFilesInClient();
        String filesText = fstat(fileNames);
        List<FileChange> allChanges = parseFileChanges(filesText);
        if (allChanges.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<FileChange>> changeMap = new HashMap<>();
        for (FileChange change : allChanges) {
            String changelistId = change.getPerforceChangelistId();
            if (!changeMap.containsKey(changelistId)) {
                changeMap.put(changelistId, new ArrayList<FileChange>());
            }
            changeMap.get(changelistId).add(change);
        }
        // make lists read only
        for (String changelistId : changeMap.keySet()) {
            List<FileChange> fileChanges = changeMap.get(changelistId);
            changeMap.put(changelistId, Collections.unmodifiableList(fileChanges));
        }
        return Collections.unmodifiableMap(changeMap);
    }

    public String getCurrentChangelistId(String id) {
        String changelistText = executeScmCommand("change -o -O " + id);
        return MatcherUtils.singleMatch(changelistText, "Change:\\s+(\\d+)");
    }

    public String getChangelistStatus(String id) {
        String changelistText = executeScmCommand("change -o -O " + id);
        return MatcherUtils.singleMatch(changelistText, "Status:\\s+(pending|submitted)");
    }

    public List<FileChange> getFileChangesForPendingChangelist(String id) {
        List<String> filesInChangelist = getOpenedFilesInChangelist(id);
        String filesText = fstat(filesInChangelist);
        return parseFileChanges(filesText);
    }

    public String diffChangelistInGitFormat(String changelistId, boolean binaryPatch, LogLevel level) {
        List<FileChange> fileChanges = getFileChangesForPendingChangelist(changelistId);
        return diffChangelistInGitFormat(fileChanges, changelistId, binaryPatch, level);
    }

    public String diffChangelistInGitFormat(List<FileChange> fileChanges, String changelistId, boolean binaryPatch, LogLevel level) {
        String filesToDiff = "";
        if (fileChanges.isEmpty()) {
            log.warn("No open file changes for changelist {}", changelistId);
            return "";
        }
        for (FileChange fileChange : fileChanges) {
            if (!filesToDiff.isEmpty()) {
                filesToDiff += " ";
            }
            filesToDiff += fullPath(fileChange.getLastFileAffected());
        }
        String diffData = diffFilesUsingGit(filesToDiff, binaryPatch, level);
        PendingChangelistToGitDiffCreator diffCreator = new PendingChangelistToGitDiffCreator(this);
        return diffCreator.create(diffData, fileChanges, binaryPatch);
    }

    public String getClientName() {
        return clientName;
    }

    @Override
    void exitIfCommandFailed(String output) {
        if (output.contains("Your session has expired, please login again")) {
            log.error("You need to relogin to Perforce, error message: {}", output);
            System.exit(1);
        }
    }

    @Override
    protected String scmExecutablePath() {
        if (getWorkingDirectory() == null) {
            return "p4";
        } else {
            return "p4 -d " + getWorkingDirectory().getPath();
        }
    }

    private List<String> parseFileNamesFromOpenedOutput(String output) {
        List<String> fileNames = new ArrayList<>();
        Matcher fileNameMatcher = Pattern.compile("(\\S+)#\\d+\\s+").matcher(output);
        while (fileNameMatcher.find()) {
            fileNames.add(fileNameMatcher.group(1));
        }
        return fileNames;
    }

    private List<FileChange> parseFileChanges(String filesText) {
        Matcher lineMatcher = Pattern.compile("\\.\\.\\.\\s+(\\w+)\\s*(\\S+$)?", Pattern.MULTILINE).matcher(filesText);
        FileChange fileChange = null;
        List<FileChange> fileChanges = new ArrayList<>();
        while (lineMatcher.find()) {
            String valueName = lineMatcher.group(1);
            String value = lineMatcher.groupCount() > 1 ? lineMatcher.group(2) : null;
            if (valueName.equals("depotFile")) {
                if (fileChange != null) {
                    fileChanges.add(fileChange);
                }
                fileChange = new FileChange(scmType);
            }
            if (fileChange != null) {
                fileChange.parseValue(valueName, value, getWorkingDirectory().getPath());
            }
        }
        if (fileChange != null) {
            fileChanges.add(fileChange);
        }
        mergeMoveDeleteAndAdds(fileChanges);
        return fileChanges;
    }

    private String diffFilesUsingGit(String filesToDiff, boolean binaryPatch, LogLevel level) {
        Map<String, String> environmentVariables = new HashMap<>();
        String binaryFlag = binaryPatch ? " --binary" : "";
        environmentVariables.put("P4DIFF", "git diff --full-index" + binaryFlag);
        return executeScmCommand(environmentVariables, "diff -du", filesToDiff, level);
    }

    private void mergeMoveDeleteAndAdds(List<FileChange> fileChanges) {
        for (FileChange fileChange : fileChanges) {
            if (fileChange.getChangeType() != renamed) {
                continue;
            }
            String movedDepotFile = fileChange.getFirstFileAffected();
            boolean foundMatchingDeleteFile = false;
            for (FileChange matchingDeleteChange : fileChanges) {
                if (matchingDeleteChange.getChangeType() == deletedAfterRename
                        && movedDepotFile.equals(matchingDeleteChange.getDepotFile())) {
                    foundMatchingDeleteFile = true;
                    String deleteClientFile = matchingDeleteChange.getLastFileAffected();
                    fileChange.replaceFileAffected(0, deleteClientFile);
                    break;
                }
            }
            if (!foundMatchingDeleteFile) {
                throw new RuntimeException("Expected to find matching move/delete action for moved depot file " + movedDepotFile);
            }
        }

        Iterator<FileChange> changeIterator = fileChanges.iterator();
        while (changeIterator.hasNext()) {
            FileChange fileChange = changeIterator.next();
            if (fileChange.getChangeType() == FileChangeType.deletedAfterRename) {
                changeIterator.remove();
            }
        }
    }

    public String sync(List<String> filesToSync, String syncChangelistId) {
        String syncVersion = StringUtils.isBlank(syncChangelistId) ? "" : "@" + syncChangelistId;
        String fileNames = appendWithDelimiter("", filesToSync, syncVersion + " ") + syncVersion;
        return executeScmCommand("sync -f {}", fileNames);
    }

    public String move(String changelistId, String fromFileName, String toFileName, String extraFlags) {
        String output = executeScmCommand("move {} -c {} {} {}", extraFlags, changelistId, fromFileName, toFileName);
        return failOutputIfMissingText(output, "moved from");
    }

    public String add(String changelistId, String fileName) {
        String output = executeScmCommand("add -c {} {}", changelistId, fileName);
        return failOutputIfMissingText(output, "opened for add");
    }

    public String openForEdit(String changelistId, String fileName) {
        String output = executeScmCommand("edit -c {} {}", changelistId, fileName);
        return failOutputIfMissingText(output, "opened for edit");
    }

    public String markForDelete(String changelistId, String fileName) {
        String output = executeScmCommand("delete -c {} {}", changelistId, fileName);
        return failOutputIfMissingText(output, "opened for delete");
    }

    public void syncPerforceFiles(List<FileChange> fileChanges, String syncChangelistId) {
        List<String> filesToSync = new ArrayList<>();
        for (FileChange diffChange : fileChanges) {
            filesToSync.add(fullPath(diffChange.getFirstFileAffected()));
            if (!diffChange.getLastFileAffected().equals(diffChange.getFirstFileAffected())) {
                filesToSync.add(fullPath(diffChange.getLastFileAffected()));
            }
        }

        if (filesToSync.isEmpty()) {
            return;
        }
        log.debug("Syncing existing perforce files {}", filesToSync.toString());
        sync(filesToSync, syncChangelistId);
    }

    public boolean revertAndResyncUnresolvedFiles(List<FileChange> changelistChanges, String versionToSyncTo) {
        if (changelistChanges == null || changelistChanges.isEmpty()) {
            return false;
        }
        List<String> unresolvedFilesToRevertAndSync = new ArrayList<>();
        for (int i = changelistChanges.size() - 1; i >= 0; i--) {
            FileChange fileChange = changelistChanges.get(i);
            if (fileChange.isUnresolved()) {
                unresolvedFilesToRevertAndSync.add(fileChange.getLastFileAffected());
            }
        }
        if (unresolvedFilesToRevertAndSync.isEmpty()) {
            return false;
        }
        log.info("Reverting and resyncing unresolved files: {}", unresolvedFilesToRevertAndSync.toString());
        revertFiles(unresolvedFilesToRevertAndSync);
        sync(unresolvedFilesToRevertAndSync, versionToSyncTo);
        return true;
    }

    public void openFilesForEditIfNeeded(String changelistId, List<FileChange> fileChanges) {
        List<String> filesToOpenForEdit = new ArrayList<>();
        List<String> filesToMoveToChangelist = new ArrayList<>();

        for (FileChange diffChange : fileChanges) {
            if (changelistId.equals(diffChange.getPerforceChangelistId())) {
                continue;
            }
            if (!FileChangeType.isEditChangeType(diffChange.getChangeType())) {
                continue;
            }

            String fullPath = fullPath(diffChange.getFirstFileAffected());
            if (StringUtils.isNotBlank(diffChange.getPerforceChangelistId())) {
                filesToMoveToChangelist.add(fullPath);
            } else {
                filesToOpenForEdit.add(fullPath);
            }
        }
        if (!filesToMoveToChangelist.isEmpty()) {
            moveFilesToChangelist(changelistId, filesToMoveToChangelist);
        }
        if (!filesToOpenForEdit.isEmpty()) {
            openForEdit(changelistId, appendWithDelimiter("", filesToOpenForEdit, " "));
        }
    }

    public void renameAddOrDeleteFiles(String changelistId, List<FileChange> fileChanges) {
        for (FileChange diffChange : fileChanges) {
            FileChangeType changeType = diffChange.getChangeType();
            String fullPathForFirstFileAffected = fullPath(diffChange.getFirstFileAffected());
            String fullPathForLastFileAffected = fullPath(diffChange.getLastFileAffected());
            if (changeType == FileChangeType.renamed || changeType == FileChangeType.renamedAndModified) {
                log.info("Renaming file {} to {}", diffChange.getFirstFileAffected(), diffChange.getLastFileAffected());
                move(changelistId, fullPathForFirstFileAffected, fullPathForLastFileAffected, "-k");
            } else if (FileChangeType.isAddChangeType(changeType)) {
                log.info("Adding file {} to perforce", diffChange.getLastFileAffected());
                add(changelistId, fullPathForLastFileAffected);
            } else if (changeType == FileChangeType.deleted) {
                log.info("Deleting {}", diffChange.getLastFileAffected());
                markForDelete(changelistId, fullPathForLastFileAffected);
            }
        }
    }

    public boolean updatePendingChangelist(String id, String description) {
        String perforceTemplate = executeScmCommand("change -o " + id);
        String amendedTemplate = updateTemplateWithDescription(perforceTemplate, description, false);
        String output = executeScmCommand("change -i", amendedTemplate, LogLevel.DEBUG);
        return changeSucceeded(output);
    }

    public void submitChangelist(String id, String description) {
        String perforceTemplate = executeScmCommand("change -o " + id);
        String amendedTemplate = updateTemplateWithDescription(perforceTemplate, description, true);
        String submitOutput = executeScmCommand("submit -f revertunchanged -i", amendedTemplate, LogLevel.INFO);
        String status = getChangelistStatus(id);
        if (!"submitted".equals(status)) {
            log.error("Changelist {} has status {}, expected submitted", id, status);
            log.error("Submit output\n{}", submitOutput);
            System.exit(1);
        }
    }

    private void exitIfExpectedTextNotPresent(String output, String expectedText, int expectedCount) {
        int matches = 0;
        int currentIndex = 0;
        while (matches++ < expectedCount) {
            int matchIndex = output.indexOf(expectedText, currentIndex);
            if (matchIndex == -1) {
                throw new RuntimeException("Unexpected output from reopen command,"
                        + expectedText + " text was not present\n" + output);
            }
            currentIndex = matchIndex + expectedText.length();
        }
    }

    private boolean changeSucceeded(String output) {
        if (output.contains("Error in change specification")) {
            log.error("Failed to apply change\n{}\n", output);
            return false;
        } else {
            return true;
        }
    }

    private File determineClientDirectoryForClientName() {
        String info = executeScmCommand("clients -e " + clientName, LogLevel.DEBUG);
        String clientDirectory = MatcherUtils.singleMatch(info, "Client\\s+" + clientName + "\\s+.+?(\\S+)\\s+'Created by");
        if (clientDirectory == null) {
            throw new RuntimeException("Failed to parse client directory for client " + clientName + "\n" + info);
        }
        return new File(clientDirectory);
    }

    private String determineClientNameForDirectory(String username) {
        String clientRoot = super.getWorkingDirectory().getPath();
        String quotedClientRoot = Pattern.quote(clientRoot);
        String info = executeScmCommand("clients -u " + username, LogLevel.DEBUG);
        String clientName = MatcherUtils.singleMatch(info, "Client\\s+(\\S+)\\s+.+?" + quotedClientRoot + "\\s+'Created by");
        if (clientName == null) {
            throw new RuntimeException("Failed to parse client name for directory " + clientRoot
                    + " in clients for user " + username + "\n" + info);
        }
        return clientName;
    }

    private String updateTemplateWithDescription(String perforceTemplate, String commitText, boolean filesExpected) {
        int descriptionIndex = perforceTemplate.indexOf("Description:");
        if (descriptionIndex == -1) {
            throw new IllegalArgumentException("Failed to find Description: in perforce template:\n" + perforceTemplate);
        }
        int filesIndex = perforceTemplate.indexOf("Files:");
        if (filesIndex == -1 && filesExpected) {
            throw new IllegalArgumentException("Failed to find Files: in perforce template, does the git commit have file changes?:\n" + perforceTemplate);
        } else if (filesIndex == -1) {
            log.debug("No files detected");
            filesIndex = perforceTemplate.length();
        }

        String amendedCommitText = "\t" + commitText.replaceAll("\n", "\n\t");

        return format("%sDescription:\n%s\n%s",
                perforceTemplate.substring(0, descriptionIndex), amendedCommitText, perforceTemplate.substring(filesIndex));
    }

    private Map<String, String> addMatchedValuesToMap(List<String> sourceValues, List<String> outputTexts, Pattern pattern) {
        Matcher matcher = pattern.matcher("");
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < sourceValues.size(); i ++) {
            matcher.reset(outputTexts.get(i));
            if (!matcher.find()) {
                throw new RuntimeException("Failed to match pattern "
                        + matcher.pattern().pattern() + " in text " + outputTexts.get(i));
            }
            values.put(sourceValues.get(i), matcher.group(1));
        }
        return values;

    }

}
