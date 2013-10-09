/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

package org.opensolaris.opengrok.history;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.opensolaris.opengrok.OpenGrokLogger;
import org.opensolaris.opengrok.util.Executor;

/**
 *
 * @author michailf
 */
public class SSCMHistoryParser implements Executor.StreamHandler {

    private final SSCMRepository repository;
    
    SSCMHistoryParser(SSCMRepository repository) {
        this.repository = repository;
    }

    private static final String FILTER_ACTION_PATTERN = "^add to branch$";
    private static final String ACTION_PATTERN = "[a-z ]+";
    private static final String USER_PATTERN = "\\w+";
    private static final String VERSION_PATTERN = "\\d+";
    private static final String TIME_PATTERN = "\\d{1,2}/\\d{1,2}/\\d{4} \\d{1,2}:\\d{2} [AP]M";
    private static final String COMMENT_START_PATTERN = "Comments - ";
    // ^([a-z ]+)(?:\[.*?\])?\s+(\w+)\s+(\d+)\s+(\d{1,2}/\d{1,2}/\d{4} \d{1,2}:\d{2} [AP]M)$\s*(?:Comments - )?
    private static final Pattern HISTORY_PATTERN = Pattern.compile("^(" + ACTION_PATTERN + ")(?:\\[(.*?)\\])?\\s+(" + USER_PATTERN + ")\\s+(" + VERSION_PATTERN + ")\\s+(" + TIME_PATTERN + ")$\\s*(?:" + COMMENT_START_PATTERN + ")?",
                                                    Pattern.MULTILINE);
    
    private static final String NEWLINE = System.getProperty("line.separator");
    
    private History history;
    
    @Override
    public void processStream(InputStream input) throws IOException {
        DateFormat df = repository.getDateFormat();
        history = new History();

        BufferedReader in = new BufferedReader(new InputStreamReader(input));
        StringBuilder total = new StringBuilder(input.available());
        String line;
        while ((line = in.readLine()) != null) {
            total.append(line).append(NEWLINE);
        }

        ArrayList<HistoryEntry> entries = new ArrayList<>();
        HistoryEntry entry = null;
        int prevEntryEnd = 0;
        
        long revisionCounter = 0;
        Matcher matcher = HISTORY_PATTERN.matcher(total);
        while (matcher.find()) {
            if (entry != null) {
                if (matcher.start() != prevEntryEnd) {
                    // Get the comment and reduce all double new lines to single
                    //  add a space as well for better formatting in RSS feeds.
                    entry.appendMessage(total.substring(prevEntryEnd, matcher.start()).replaceAll("(\\r?\\n){2}", " $1").trim());
                }
                entries.add(0, entry);
                entry = null;
            }
            if (!matcher.group(1).matches(FILTER_ACTION_PATTERN)) {
                String revision = matcher.group(4);
                String author = matcher.group(3);
                String branch = matcher.group(2);

                long currentRevision = Long.parseLong(revision);
                if (revisionCounter < currentRevision) {
                    revisionCounter = currentRevision;
                    
                    entry = new HistoryEntry();
                    // Add branch name to message.  Helps when branch name is used
                    //   as indicator of why promote was made.
                    if (branch != null)
                        entry.appendMessage("[" + branch + "] ");
                    entry.setAuthor(author);
                    entry.setRevision(revision);
                    try {
                        entry.setDate(df.parse(matcher.group(5)));
                    } catch (ParseException ex) {
                        OpenGrokLogger.getLogger().log(Level.WARNING, "Failed to parse date: '" + matcher.group(5) + "'", ex);
                    }
                    entry.setActive(true);
                }
            }
            prevEntryEnd = matcher.end();
        }

        if (entry != null) {
            if (total.length() != prevEntryEnd) {
                // Get the comment and reduce all double new lines to single
                //  add a space as well for better formatting in RSS feeds.
                entry.appendMessage(total.substring(prevEntryEnd).replaceAll("(\\r?\\n){2}", " $1").trim());
            }
            entries.add(0, entry);
        }
        history.setHistoryEntries(entries);
    }

    History parse(File file, String sinceRevision) throws HistoryException {
        try {
            Executor executor = repository.getHistoryLogExecutor(file, sinceRevision);
            int status = executor.exec(true, this);

            if (status != 0) {
                throw new HistoryException("Failed to get history for: \"" +
                                           file.getAbsolutePath() + "\" Exit code: " + status);
            }
        } catch (IOException e) {
            throw new HistoryException("Failed to get history for: \"" +
                                       file.getAbsolutePath() + "\"", e);
        }

        return history;
    }
}
