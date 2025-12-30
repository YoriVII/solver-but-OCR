package com.wordscapes.ocr;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

public class Trie {
    static class TrieNode {
        TrieNode[] children = new TrieNode[26];
        boolean isEndOfWord;
    }

    TrieNode root = new TrieNode();

    public void insert(String word) {
        TrieNode node = root;
        for (char c : word.toCharArray()) {
            int index = c - 'A';
            if (node.children[index] == null)
                node.children[index] = new TrieNode();
            node = node.children[index];
        }
        node.isEndOfWord = true;
    }

    public void loadDictionary(Context context) {
        try {
            // Load from assets/words.txt
            BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open("words.txt")));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) insert(line.trim().toUpperCase());
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public List<String> solve(String letters) {
        List<String> results = new ArrayList<>();
        boolean[] used = new boolean[letters.length()];
        char[] charArray = letters.toCharArray();
        Arrays.sort(charArray); // Sort to handle duplicates easier
        search(root, charArray, used, new StringBuilder(), results);
        return new ArrayList<>(new HashSet<>(results)); // Remove dupes
    }

    private void search(TrieNode node, char[] chars, boolean[] used, StringBuilder sb, List<String> results) {
        if (node.isEndOfWord) results.add(sb.toString());

        for (int i = 0; i < chars.length; i++) {
            if (used[i]) continue;
            // Skip duplicates to prevent redundant processing
            if (i > 0 && chars[i] == chars[i - 1] && !used[i - 1]) continue;

            int index = chars[i] - 'A';
            if (node.children[index] != null) {
                used[i] = true;
                sb.append(chars[i]);
                search(node.children[index], chars, used, sb, results);
                sb.deleteCharAt(sb.length() - 1);
                used[i] = false;
            }
        }
    }
}
