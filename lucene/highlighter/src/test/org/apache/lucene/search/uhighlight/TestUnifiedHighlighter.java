/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.search.uhighlight;

import com.carrotsearch.randomizedtesting.annotations.Name;
import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.DelegatingAnalyzerWrapper;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.Weight;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter.HighlightFlag;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.analysis.MockAnalyzer;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.util.QueryBuilder;
import org.apache.lucene.util.automaton.Automata;
import org.apache.lucene.util.automaton.CharacterRunAutomaton;
import org.apache.lucene.util.automaton.RegExp;

public class TestUnifiedHighlighter extends UnifiedHighlighterTestBase {
  @ParametersFactory
  public static Iterable<Object[]> parameters() {
    return parametersFactoryList();
  }

  public TestUnifiedHighlighter(@Name("fieldType") FieldType fieldType) {
    super(fieldType);
  }

  static Set<HighlightFlag> generateRandomHighlightFlags(EnumSet<HighlightFlag> requiredFlags) {
    final EnumSet<HighlightFlag> result = EnumSet.copyOf(requiredFlags);
    int r = random().nextInt();
    for (HighlightFlag highlightFlag : HighlightFlag.values()) {
      if (((1 << highlightFlag.ordinal()) & r) == 0) {
        result.add(highlightFlag);
      }
    }
    if (result.contains(HighlightFlag.WEIGHT_MATCHES)) {
      // these two are required for WEIGHT_MATCHES
      result.add(HighlightFlag.MULTI_TERM_QUERY);
      result.add(HighlightFlag.PHRASES);
    }
    return result;
  }

  /** This randomized test method uses builder from the UH class. */
  static UnifiedHighlighter randomUnifiedHighlighter(
      IndexSearcher searcher, Analyzer indexAnalyzer) {
    return randomUnifiedHighlighter(
        searcher, indexAnalyzer, EnumSet.noneOf(HighlightFlag.class), null);
  }

  static UnifiedHighlighter randomUnifiedHighlighter(
      IndexSearcher searcher,
      Analyzer indexAnalyzer,
      EnumSet<HighlightFlag> mandatoryFlags,
      Boolean requireFieldMatch) {
    UnifiedHighlighter.Builder uhBuilder = new UnifiedHighlighter.Builder(searcher, indexAnalyzer);
    return randomUnifiedHighlighter(uhBuilder, mandatoryFlags, requireFieldMatch);
  }

  static UnifiedHighlighter randomUnifiedHighlighter(UnifiedHighlighter.Builder uhBuilder) {
    return randomUnifiedHighlighter(uhBuilder, EnumSet.noneOf(HighlightFlag.class), null);
  }

  static UnifiedHighlighter randomUnifiedHighlighter(
      UnifiedHighlighter.Builder uhBuilder,
      EnumSet<HighlightFlag> mandatoryFlags,
      Boolean requireFieldMatch) {
    uhBuilder.withCacheFieldValCharsThreshold(random().nextInt(100));
    if (requireFieldMatch == Boolean.FALSE
        || (requireFieldMatch == null && random().nextBoolean())) {
      uhBuilder.withFieldMatcher(_ -> true); // requireFieldMatch==false
    }
    return overriddenBuilderForTests(uhBuilder, mandatoryFlags).build();
  }

  static UnifiedHighlighter overrideFieldMatcherForTests(
      UnifiedHighlighter original, Predicate<String> value, String fieldName) {
    return UnifiedHighlighter.builder(original.getIndexSearcher(), original.getIndexAnalyzer())
        .withFlags(original.getFlags(fieldName))
        .withCacheFieldValCharsThreshold(original.getCacheFieldValCharsThreshold())
        .withFieldMatcher(value)
        .build();
  }

  static UnifiedHighlighter.Builder overriddenBuilderForTests(
      UnifiedHighlighter.Builder uhBuilder, EnumSet<HighlightFlag> mandatoryFlags) {
    return new UnifiedHighlighter.Builder(
        uhBuilder.getIndexSearcher(), uhBuilder.getIndexAnalyzer()) {
      Set<HighlightFlag> flags;

      @Override
      public UnifiedHighlighter build() {
        return new UnifiedHighlighter(uhBuilder) {
          @Override
          protected Set<HighlightFlag> evaluateFlags(Builder uhBuilder) {
            if (Objects.nonNull(flags)) {
              return flags;
            }
            return flags = generateRandomHighlightFlags(mandatoryFlags);
          }
        };
      }
    };
  }

  //
  //  Tests below were ported from the PostingsHighlighter. Possibly augmented.  Far below are newer
  // tests.
  //

  public void testBasics() throws Exception {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Field body = new Field("body", "", fieldType);
    Document doc = new Document();
    doc.add(body);

    body.setStringValue(
        "This is a test. Just a test highlighting from postings. Feel free to ignore.");
    iw.addDocument(doc);
    body.setStringValue("Highlighting the first term. Hope it works.");
    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighter = randomUnifiedHighlighter(searcher, indexAnalyzer);
    Query query = new TermQuery(new Term("body", "highlighting"));
    TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
    assertEquals(2, topDocs.totalHits.value());
    String[] snippets = highlighter.highlight("body", query, topDocs);
    assertEquals(2, snippets.length);
    assertEquals("Just a test <b>highlighting</b> from postings. ", snippets[0]);
    assertEquals("<b>Highlighting</b> the first term. ", snippets[1]);

    ir.close();
  }

  public void testFormatWithMatchExceedingContentLength2() throws Exception {

    String bodyText = "123 TEST 01234 TEST";

    String[] snippets = formatWithMatchExceedingContentLength(bodyText);

    assertEquals(1, snippets.length);
    assertEquals("123 <b>TEST</b> 01234 TE", snippets[0]);
  }

  public void testFormatWithMatchExceedingContentLength3() throws Exception {

    String bodyText = "123 5678 01234 TEST TEST";

    String[] snippets = formatWithMatchExceedingContentLength(bodyText);

    assertEquals(1, snippets.length);
    assertEquals("123 5678 01234 TE", snippets[0]);
  }

  public void testFormatWithMatchExceedingContentLength() throws Exception {

    String bodyText = "123 5678 01234 TEST";

    String[] snippets = formatWithMatchExceedingContentLength(bodyText);

    assertEquals(1, snippets.length);
    // LUCENE-5166: no snippet
    assertEquals("123 5678 01234 TE", snippets[0]);
  }

  private String[] formatWithMatchExceedingContentLength(String bodyText) throws IOException {

    int maxLength = 17;

    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    final Field body = new Field("body", bodyText, fieldType);

    Document doc = new Document();
    doc.add(body);

    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);

    Query query = new TermQuery(new Term("body", "test"));

    TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
    assertEquals(1, topDocs.totalHits.value());

    UnifiedHighlighter.Builder uhBuilder =
        new UnifiedHighlighter.Builder(searcher, indexAnalyzer).withMaxLength(maxLength);
    UnifiedHighlighter highlighter = randomUnifiedHighlighter(uhBuilder);
    String[] snippets = highlighter.highlight("body", query, topDocs);

    ir.close();
    return snippets;
  }

  // simple test highlighting last word.
  public void testHighlightLastWord() throws Exception {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Field body = new Field("body", "", fieldType);
    Document doc = new Document();
    doc.add(body);

    body.setStringValue("This is a test");
    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighter = randomUnifiedHighlighter(searcher, indexAnalyzer);
    Query query = new TermQuery(new Term("body", "test"));
    TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
    assertEquals(1, topDocs.totalHits.value());
    String[] snippets = highlighter.highlight("body", query, topDocs);
    assertEquals(1, snippets.length);
    assertEquals("This is a <b>test</b>", snippets[0]);

    ir.close();
  }

  // simple test with one sentence documents.
  public void testOneSentence() throws Exception {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Field body = new Field("body", "", fieldType);
    Document doc = new Document();
    doc.add(body);

    body.setStringValue("This is a test.");
    iw.addDocument(doc);
    body.setStringValue("Test a one sentence document.");
    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighter = randomUnifiedHighlighter(searcher, indexAnalyzer);
    Query query = new TermQuery(new Term("body", "test"));
    TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
    assertEquals(2, topDocs.totalHits.value());
    String[] snippets = highlighter.highlight("body", query, topDocs);
    assertEquals(2, snippets.length);
    assertEquals("This is a <b>test</b>.", snippets[0]);
    assertEquals("<b>Test</b> a one sentence document.", snippets[1]);

    ir.close();
  }

  // simple test with multiple values that make a result longer than maxLength.
  public void testMaxLengthWithMultivalue() throws Exception {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Document doc = new Document();

    final String value = "This is a multivalued field. Sentencetwo field.";
    doc.add(new Field("body", value, fieldType));
    doc.add(new Field("body", value, fieldType));
    doc.add(new Field("body", value, fieldType));

    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter.Builder uhBuilder =
        new UnifiedHighlighter.Builder(searcher, indexAnalyzer)
            .withMaxLength(value.length() * 2 + 1);
    UnifiedHighlighter highlighter = randomUnifiedHighlighter(uhBuilder);
    Query query = new TermQuery(new Term("body", "field"));
    TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
    assertEquals(1, topDocs.totalHits.value());
    String[] snippets = highlighter.highlight("body", query, topDocs, 10);
    assertEquals(1, snippets.length);
    String highlightedValue = "This is a multivalued <b>field</b>. Sentencetwo <b>field</b>.";
    assertEquals(highlightedValue + "... " + highlightedValue, snippets[0]);

    ir.close();
  }

  public void testMultipleFields() throws Exception {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Field body = new Field("body", "", fieldType);
    Field title = new Field("title", "", randomFieldType(random()));
    Document doc = new Document();
    doc.add(body);
    doc.add(title);

    body.setStringValue(
        "This is a test. Just a test highlighting from postings. Feel free to ignore.");
    title.setStringValue("I am hoping for the best.");
    iw.addDocument(doc);
    body.setStringValue("Highlighting the first term. Hope it works.");
    title.setStringValue("But best may not be good enough.");
    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighter = randomUnifiedHighlighter(searcher, indexAnalyzer);
    BooleanQuery query =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("body", "highlighting")), BooleanClause.Occur.SHOULD)
            .add(new TermQuery(new Term("title", "best")), BooleanClause.Occur.SHOULD)
            .build();
    TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
    assertEquals(2, topDocs.totalHits.value());
    Map<String, String[]> snippets =
        highlighter.highlightFields(new String[] {"body", "title"}, query, topDocs);
    assertEquals(2, snippets.size());
    assertEquals("Just a test <b>highlighting</b> from postings. ", snippets.get("body")[0]);
    assertEquals("<b>Highlighting</b> the first term. ", snippets.get("body")[1]);
    assertEquals("I am hoping for the <b>best</b>.", snippets.get("title")[0]);
    assertEquals("But <b>best</b> may not be good enough.", snippets.get("title")[1]);
    ir.close();
  }

  public void testMultipleTerms() throws Exception {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Field body = new Field("body", "", fieldType);
    Document doc = new Document();
    doc.add(body);

    body.setStringValue(
        "This is a test. Just a test highlighting from postings. Feel free to ignore.");
    iw.addDocument(doc);
    body.setStringValue("Highlighting the first term. Hope it works.");
    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighter = randomUnifiedHighlighter(searcher, indexAnalyzer);
    BooleanQuery query =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("body", "highlighting")), BooleanClause.Occur.SHOULD)
            .add(new TermQuery(new Term("body", "just")), BooleanClause.Occur.SHOULD)
            .add(new TermQuery(new Term("body", "first")), BooleanClause.Occur.SHOULD)
            .build();
    TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
    assertEquals(2, topDocs.totalHits.value());
    String[] snippets = highlighter.highlight("body", query, topDocs);
    assertEquals(2, snippets.length);
    assertEquals("<b>Just</b> a test <b>highlighting</b> from postings. ", snippets[0]);
    assertEquals("<b>Highlighting</b> the <b>first</b> term. ", snippets[1]);

    ir.close();
  }

  public void testMultiplePassages() throws Exception {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Field body = new Field("body", "", fieldType);
    Document doc = new Document();
    doc.add(body);

    body.setStringValue(
        "This is a test. Just a test highlighting from postings. Feel free to ignore.");
    iw.addDocument(doc);
    body.setStringValue("This test is another test. Not a good sentence. Test test test test.");
    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighter = randomUnifiedHighlighter(searcher, indexAnalyzer);
    Query query = new TermQuery(new Term("body", "test"));
    TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
    assertEquals(2, topDocs.totalHits.value());
    String[] snippets = highlighter.highlight("body", query, topDocs, 2);
    assertEquals(2, snippets.length);
    assertEquals(
        "This is a <b>test</b>. Just a <b>test</b> highlighting from postings. ", snippets[0]);
    assertEquals(
        "This <b>test</b> is another <b>test</b>. ... <b>Test</b> <b>test</b> <b>test</b> <b>test</b>.",
        snippets[1]);

    ir.close();
  }

  public void testBuddhism() throws Exception {
    String text =
        "This eight-volume set brings together seminal papers in Buddhist studies from a vast "
            + "range of academic disciplines published over the last forty years. With a new introduction "
            + "by the editor, this collection is a unique and unrivalled research resource for both "
            + "student and scholar. Coverage includes: - Buddhist origins; early history of Buddhism in "
            + "South and Southeast Asia - early Buddhist Schools and Doctrinal History; Theravada Doctrine "
            + "- the Origins and nature of Mahayana Buddhism; some Mahayana religious topics - Abhidharma "
            + "and Madhyamaka - Yogacara, the Epistemological tradition, and Tathagatagarbha - Tantric "
            + "Buddhism (Including China and Japan); Buddhism in Nepal and Tibet - Buddhism in South and "
            + "Southeast Asia, and - Buddhism in China, East Asia, and Japan.";
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Field body = new Field("body", text, fieldType);
    Document document = new Document();
    document.add(body);
    iw.addDocument(document);
    IndexReader ir = iw.getReader();
    iw.close();
    IndexSearcher searcher = newSearcher(ir);
    PhraseQuery query =
        new PhraseQuery.Builder()
            .add(new Term("body", "buddhist"))
            .add(new Term("body", "origins"))
            .build();
    TopDocs topDocs = searcher.search(query, 10);
    assertEquals(1, topDocs.totalHits.value());
    UnifiedHighlighter.Builder uhBuilder =
        new UnifiedHighlighter.Builder(searcher, indexAnalyzer).withHighlightPhrasesStrictly(false);
    UnifiedHighlighter highlighter = randomUnifiedHighlighter(uhBuilder);
    String[] snippets = highlighter.highlight("body", query, topDocs, 2);
    assertEquals(1, snippets.length);
    if (highlighter
        .getFlags("body")
        .containsAll(EnumSet.of(HighlightFlag.WEIGHT_MATCHES, HighlightFlag.PHRASES))) {
      assertTrue(snippets[0], snippets[0].contains("<b>Buddhist origins</b>"));
    } else {
      assertTrue(snippets[0], snippets[0].contains("<b>Buddhist</b> <b>origins</b>"));
    }
    ir.close();
  }

  public void testHighlighterDefaultFlags() throws Exception {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();
    Document document = new Document();
    document.add(new Field("body", "test body", fieldType));
    iw.addDocument(document);
    IndexReader ir = iw.getReader();
    iw.close();
    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighter = UnifiedHighlighter.builder(searcher, indexAnalyzer).build();
    Set<HighlightFlag> flags = highlighter.getFlags("body");
    assertTrue(flags.contains(HighlightFlag.PHRASES));
    assertTrue(flags.contains(HighlightFlag.MULTI_TERM_QUERY));
    assertTrue(flags.contains(HighlightFlag.PASSAGE_RELEVANCY_OVER_SPEED));
    assertTrue(flags.contains(HighlightFlag.WEIGHT_MATCHES));
    // if more flags are added, bump the number below and add an assertTrue or assertFalse above
    assertEquals(4, HighlightFlag.values().length);
    ir.close();
  }

  public void testCuriousGeorge() throws Exception {
    String text =
        "It’s the formula for success for preschoolers—Curious George and fire trucks! "
            + "Curious George and the Firefighters is a story based on H. A. and Margret Rey’s "
            + "popular primate and painted in the original watercolor and charcoal style. "
            + "Firefighters are a famously brave lot, but can they withstand a visit from one curious monkey?";
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Field body = new Field("body", text, fieldType);
    Document document = new Document();
    document.add(body);
    iw.addDocument(document);
    IndexReader ir = iw.getReader();
    iw.close();
    IndexSearcher searcher = newSearcher(ir);
    PhraseQuery query =
        new PhraseQuery.Builder()
            .add(new Term("body", "curious"))
            .add(new Term("body", "george"))
            .build();
    TopDocs topDocs = searcher.search(query, 10);
    assertEquals(1, topDocs.totalHits.value());
    UnifiedHighlighter.Builder uhBuilder =
        new UnifiedHighlighter.Builder(searcher, indexAnalyzer).withHighlightPhrasesStrictly(false);
    UnifiedHighlighter highlighter = randomUnifiedHighlighter(uhBuilder);
    String[] snippets = highlighter.highlight("body", query, topDocs, 2);
    assertEquals(1, snippets.length);
    assertFalse(snippets[0].contains("<b>Curious</b>Curious"));
    ir.close();
  }

  public void testCambridgeMA() throws Exception {
    BufferedReader r =
        new BufferedReader(
            new InputStreamReader(
                this.getClass().getResourceAsStream("CambridgeMA.utf8"), StandardCharsets.UTF_8));
    String text = r.readLine();
    r.close();
    RandomIndexWriter iw = newIndexOrderPreservingWriter();
    Field body = new Field("body", text, fieldType);
    Document document = new Document();
    document.add(body);
    iw.addDocument(document);
    IndexReader ir = iw.getReader();
    iw.close();
    IndexSearcher searcher = newSearcher(ir);
    BooleanQuery query =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("body", "porter")), BooleanClause.Occur.SHOULD)
            .add(new TermQuery(new Term("body", "square")), BooleanClause.Occur.SHOULD)
            .add(new TermQuery(new Term("body", "massachusetts")), BooleanClause.Occur.SHOULD)
            .build();
    TopDocs topDocs = searcher.search(query, 10);
    assertEquals(1, topDocs.totalHits.value());
    UnifiedHighlighter.Builder uhBuilder =
        new UnifiedHighlighter.Builder(searcher, indexAnalyzer)
            .withMaxLength(Integer.MAX_VALUE - 1);
    UnifiedHighlighter highlighter = randomUnifiedHighlighter(uhBuilder);
    String[] snippets = highlighter.highlight("body", query, topDocs, 2);
    assertEquals(1, snippets.length);
    assertTrue(snippets[0].contains("<b>Square</b>"));
    assertTrue(snippets[0].contains("<b>Porter</b>"));
    ir.close();
  }

  public void testPassageRanking() throws Exception {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Field body = new Field("body", "", fieldType);
    Document doc = new Document();
    doc.add(body);

    body.setStringValue(
        "This is a test.  Just highlighting from postings. This is also a much sillier test.  Feel free to test test test test test test test.");
    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighter = randomUnifiedHighlighter(searcher, indexAnalyzer);
    Query query = new TermQuery(new Term("body", "test"));
    TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
    assertEquals(1, topDocs.totalHits.value());
    String[] snippets = highlighter.highlight("body", query, topDocs, 2);
    assertEquals(1, snippets.length);
    assertEquals(
        "This is a <b>test</b>.  ... Feel free to <b>test</b> <b>test</b> <b>test</b> <b>test</b> <b>test</b> <b>test</b> <b>test</b>.",
        snippets[0]);

    ir.close();
  }

  public void testBooleanMustNot() throws Exception {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Field body =
        new Field(
            "body", "This sentence has both terms.  This sentence has only terms.", fieldType);
    Document document = new Document();
    document.add(body);
    iw.addDocument(document);
    IndexReader ir = iw.getReader();
    iw.close();
    IndexSearcher searcher = newSearcher(ir);

    BooleanQuery query2 =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("body", "both")), BooleanClause.Occur.MUST_NOT)
            .build();

    BooleanQuery query =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("body", "terms")), BooleanClause.Occur.SHOULD)
            .add(query2, BooleanClause.Occur.SHOULD)
            .build();

    TopDocs topDocs = searcher.search(query, 10);
    assertEquals(1, topDocs.totalHits.value());
    UnifiedHighlighter.Builder uhBuilder =
        new UnifiedHighlighter.Builder(searcher, indexAnalyzer)
            .withMaxLength(Integer.MAX_VALUE - 1);
    UnifiedHighlighter highlighter = randomUnifiedHighlighter(uhBuilder);
    String[] snippets = highlighter.highlight("body", query, topDocs, 2);
    assertEquals(1, snippets.length);
    assertFalse(snippets[0].contains("<b>both</b>"));
    ir.close();
  }

  public void testHighlightAllText() throws Exception {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Field body = new Field("body", "", fieldType);
    Document doc = new Document();
    doc.add(body);

    body.setStringValue(
        "This is a test.  Just highlighting from postings. This is also a much sillier test.  Feel free to test test test test test test test.");
    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter.Builder uhBuilder =
        new UnifiedHighlighter.Builder(searcher, indexAnalyzer)
            .withBreakIterator(WholeBreakIterator::new)
            .withMaxLength(10000);
    UnifiedHighlighter highlighter = randomUnifiedHighlighter(uhBuilder);
    Query query = new TermQuery(new Term("body", "test"));
    TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
    assertEquals(1, topDocs.totalHits.value());
    String[] snippets = highlighter.highlight("body", query, topDocs, 2);
    assertEquals(1, snippets.length);
    assertEquals(
        "This is a <b>test</b>.  Just highlighting from postings. This is also a much sillier <b>test</b>.  Feel free to <b>test</b> <b>test</b> <b>test</b> <b>test</b> <b>test</b> <b>test</b> <b>test</b>.",
        snippets[0]);

    ir.close();
  }

  public void testSpecificDocIDs() throws Exception {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Field body = new Field("body", "", fieldType);
    Document doc = new Document();
    doc.add(body);

    body.setStringValue(
        "This is a test. Just a test highlighting from postings. Feel free to ignore.");
    iw.addDocument(doc);
    body.setStringValue("Highlighting the first term. Hope it works.");
    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighter = randomUnifiedHighlighter(searcher, indexAnalyzer);
    Query query = new TermQuery(new Term("body", "highlighting"));
    TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
    assertEquals(2, topDocs.totalHits.value());
    ScoreDoc[] hits = topDocs.scoreDocs;
    int[] docIDs = new int[2];
    docIDs[0] = hits[0].doc;
    docIDs[1] = hits[1].doc;
    String[] snippets =
        highlighter
            .highlightFields(new String[] {"body"}, query, docIDs, new int[] {1})
            .get("body");
    assertEquals(2, snippets.length);
    assertEquals("Just a test <b>highlighting</b> from postings. ", snippets[0]);
    assertEquals("<b>Highlighting</b> the first term. ", snippets[1]);

    ir.close();
  }

  public void testCustomFieldValueSource() throws Exception {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Document doc = new Document();

    final String text =
        "This is a test.  Just highlighting from postings. This is also a much sillier test.  Feel free to test test test test test test test.";
    Field body = new Field("body", text, fieldType);
    doc.add(body);
    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);

    UnifiedHighlighter.Builder uhBuilder = new UnifiedHighlighter.Builder(searcher, indexAnalyzer);
    UnifiedHighlighter highlighter =
        new UnifiedHighlighter.Builder(searcher, indexAnalyzer) {
          @Override
          public UnifiedHighlighter build() {
            return new UnifiedHighlighter(uhBuilder) {
              @Override
              protected List<CharSequence[]> loadFieldValues(
                  String[] fields, DocIdSetIterator docIter, int cacheCharsThreshold)
                  throws IOException {
                assert fields.length == 1;
                assert docIter.cost() == 1;
                docIter.nextDoc();
                return Collections.singletonList(new CharSequence[] {text});
              }

              @Override
              protected BreakIterator getBreakIterator(String field) {
                return new WholeBreakIterator();
              }
            };
          }
        }.build();
    Query query = new TermQuery(new Term("body", "test"));
    TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
    assertEquals(1, topDocs.totalHits.value());
    String[] snippets = highlighter.highlight("body", query, topDocs, 2);
    assertEquals(1, snippets.length);
    assertEquals(
        "This is a <b>test</b>.  Just highlighting from postings. This is also a much sillier <b>test</b>.  Feel free to <b>test</b> <b>test</b> <b>test</b> <b>test</b> <b>test</b> <b>test</b> <b>test</b>.",
        snippets[0]);

    ir.close();
  }

  /** Make sure highlighter returns first N sentences if there were no hits. */
  public void testEmptyHighlights() throws Exception {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Document doc = new Document();

    Field body =
        new Field(
            "body",
            "test this is.  another sentence this test has.  far away is that planet.",
            fieldType);
    doc.add(body);
    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighter = randomUnifiedHighlighter(searcher, indexAnalyzer);
    Query query = new TermQuery(new Term("body", "highlighting"));
    int[] docIDs = new int[] {0};
    String[] snippets =
        highlighter
            .highlightFields(new String[] {"body"}, query, docIDs, new int[] {2})
            .get("body");
    assertEquals(1, snippets.length);
    assertEquals("test this is.  another sentence this test has.  ", snippets[0]);

    ir.close();
  }

  /** Not empty but nothing analyzes. Ensures we address null term-vectors. */
  public void testNothingAnalyzes() throws Exception {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Document doc = new Document();
    doc.add(new Field("body", " ", fieldType)); // just a space! (thus not empty)
    doc.add(newTextField("id", "id", Field.Store.YES));
    iw.addDocument(doc);

    doc = new Document();
    doc.add(new Field("body", "something", fieldType));
    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighter = randomUnifiedHighlighter(searcher, indexAnalyzer);
    int docID = searcher.search(new TermQuery(new Term("id", "id")), 1).scoreDocs[0].doc;

    Query query = new TermQuery(new Term("body", "highlighting"));
    int[] docIDs = new int[1];
    docIDs[0] = docID;
    String[] snippets =
        highlighter
            .highlightFields(new String[] {"body"}, query, docIDs, new int[] {2})
            .get("body");
    assertEquals(1, snippets.length);
    assertEquals(" ", snippets[0]);

    ir.close();
  }

  /** Make sure highlighter we can customize how emtpy highlight is returned. */
  public void testCustomEmptyHighlights() throws Exception {
    indexAnalyzer.setPositionIncrementGap(10);
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Document doc = new Document();

    Field body =
        new Field(
            "body",
            "test this is.  another sentence this test has.  far away is that planet.",
            fieldType);
    doc.add(body);
    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter.Builder uhBuilder =
        new UnifiedHighlighter.Builder(searcher, indexAnalyzer)
            .withMaxNoHighlightPassages(0); // don't want any default summary
    UnifiedHighlighter highlighter = randomUnifiedHighlighter(uhBuilder);
    Query query = new TermQuery(new Term("body", "highlighting"));
    int[] docIDs = new int[] {0};
    String[] snippets =
        highlighter
            .highlightFields(new String[] {"body"}, query, docIDs, new int[] {2})
            .get("body");
    assertEquals(1, snippets.length);
    assertNull(snippets[0]);

    ir.close();
  }

  /** Make sure highlighter returns whole text when there are no hits and BreakIterator is null. */
  public void testEmptyHighlightsWhole() throws Exception {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Document doc = new Document();

    Field body =
        new Field(
            "body",
            "test this is.  another sentence this test has.  far away is that planet.",
            fieldType);
    doc.add(body);
    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighter =
        UnifiedHighlighter.builder(searcher, indexAnalyzer)
            .withBreakIterator(WholeBreakIterator::new)
            .build();
    Query query = new TermQuery(new Term("body", "highlighting"));
    int[] docIDs = new int[] {0};
    String[] snippets =
        highlighter
            .highlightFields(new String[] {"body"}, query, docIDs, new int[] {2})
            .get("body");
    assertEquals(1, snippets.length);
    assertEquals(
        "test this is.  another sentence this test has.  far away is that planet.", snippets[0]);

    ir.close();
  }

  /** Make sure highlighter is OK with entirely missing field. */
  public void testFieldIsMissing() throws Exception {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Document doc = new Document();

    Field body =
        new Field(
            "body",
            "test this is.  another sentence this test has.  far away is that planet.",
            fieldType);
    doc.add(body);
    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighter = randomUnifiedHighlighter(searcher, indexAnalyzer);
    Query query = new TermQuery(new Term("bogus", "highlighting"));
    int[] docIDs = new int[] {0};
    String[] snippets =
        highlighter
            .highlightFields(new String[] {"bogus"}, query, docIDs, new int[] {2})
            .get("bogus");
    assertEquals(1, snippets.length);
    assertNull(snippets[0]);

    ir.close();
  }

  public void testFieldIsJustSpace() throws Exception {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Document doc = new Document();
    doc.add(new Field("body", "   ", fieldType));
    doc.add(newTextField("id", "id", Field.Store.YES));
    iw.addDocument(doc);

    doc = new Document();
    doc.add(new Field("body", "something", fieldType));
    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighter = randomUnifiedHighlighter(searcher, indexAnalyzer);
    int docID = searcher.search(new TermQuery(new Term("id", "id")), 1).scoreDocs[0].doc;

    Query query = new TermQuery(new Term("body", "highlighting"));
    int[] docIDs = new int[1];
    docIDs[0] = docID;
    String[] snippets =
        highlighter
            .highlightFields(new String[] {"body"}, query, docIDs, new int[] {2})
            .get("body");
    assertEquals(1, snippets.length);
    assertEquals("   ", snippets[0]);

    ir.close();
  }

  public void testFieldIsEmptyString() throws Exception {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Document doc = new Document();
    doc.add(new Field("body", "", fieldType));
    doc.add(newTextField("id", "id", Field.Store.YES));
    iw.addDocument(doc);

    doc = new Document();
    doc.add(new Field("body", "something", fieldType));
    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighter = randomUnifiedHighlighter(searcher, indexAnalyzer);
    int docID = searcher.search(new TermQuery(new Term("id", "id")), 1).scoreDocs[0].doc;

    Query query = new TermQuery(new Term("body", "highlighting"));
    int[] docIDs = new int[1];
    docIDs[0] = docID;
    String[] snippets =
        highlighter
            .highlightFields(new String[] {"body"}, query, docIDs, new int[] {2})
            .get("body");
    assertEquals(1, snippets.length);
    assertNull(snippets[0]);

    ir.close();
  }

  public void testMultipleDocs() throws Exception {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    int numDocs = atLeast(100);
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      String content = "the answer is " + i;
      if ((i & 1) == 0) {
        content += " some more terms";
      }
      doc.add(new Field("body", content, fieldType));
      doc.add(newStringField("id", "" + i, Field.Store.YES));
      iw.addDocument(doc);

      if (random().nextInt(10) == 2) {
        iw.commit();
      }
    }

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter.Builder uhBuilder =
        new UnifiedHighlighter.Builder(searcher, indexAnalyzer)
            .withCacheFieldValCharsThreshold(
                random().nextInt(10) * 10); // 0 thru 90 intervals of 10
    UnifiedHighlighter highlighter = randomUnifiedHighlighter(uhBuilder);
    Query query = new TermQuery(new Term("body", "answer"));
    TopDocs hits = searcher.search(query, numDocs);
    assertEquals(numDocs, hits.totalHits.value());

    String[] snippets = highlighter.highlight("body", query, hits);
    assertEquals(numDocs, snippets.length);
    for (int hit = 0; hit < numDocs; hit++) {
      Document doc = searcher.storedFields().document(hits.scoreDocs[hit].doc);
      int id = Integer.parseInt(doc.get("id"));
      String expected = "the <b>answer</b> is " + id;
      if ((id & 1) == 0) {
        expected += " some more terms";
      }
      assertEquals(expected, snippets[hit]);
    }

    ir.close();
  }

  public void testMultipleSnippetSizes() throws Exception {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Field body = new Field("body", "", fieldType);
    Field title = new Field("title", "", randomFieldType(random()));
    Document doc = new Document();
    doc.add(body);
    doc.add(title);

    body.setStringValue(
        "This is a test. Just a test highlighting from postings. Feel free to ignore.");
    title.setStringValue(
        "This is a test. Just a test highlighting from postings. Feel free to ignore.");
    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighter = randomUnifiedHighlighter(searcher, indexAnalyzer);
    BooleanQuery query =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("body", "test")), BooleanClause.Occur.SHOULD)
            .add(new TermQuery(new Term("title", "test")), BooleanClause.Occur.SHOULD)
            .build();
    Map<String, String[]> snippets =
        highlighter.highlightFields(
            new String[] {"title", "body"}, query, new int[] {0}, new int[] {1, 2});
    String titleHighlight = snippets.get("title")[0];
    String bodyHighlight = snippets.get("body")[0];
    assertEquals("This is a <b>test</b>. ", titleHighlight);
    assertEquals(
        "This is a <b>test</b>. Just a <b>test</b> highlighting from postings. ", bodyHighlight);
    ir.close();
  }

  public void testEncode() throws Exception {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Field body = new Field("body", "", fieldType);
    Document doc = new Document();
    doc.add(body);

    body.setStringValue(
        "This is a test. Just a test highlighting from <i>postings</i>. Feel free to ignore.");
    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighter =
        UnifiedHighlighter.builder(searcher, indexAnalyzer)
            .withFormatter(new DefaultPassageFormatter("<b>", "</b>", "... ", true))
            .build();
    Query query = new TermQuery(new Term("body", "highlighting"));
    TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
    assertEquals(1, topDocs.totalHits.value());
    String[] snippets = highlighter.highlight("body", query, topDocs);
    assertEquals(1, snippets.length);
    assertEquals(
        "Just a test <b>highlighting</b> from &lt;i&gt;postings&lt;&#x2F;i&gt;. ", snippets[0]);

    ir.close();
  }

  // LUCENE-4906
  public void testObjectFormatter() throws Exception {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Field body = new Field("body", "", fieldType);
    Document doc = new Document();
    doc.add(body);

    body.setStringValue(
        "This is a test. Just a test highlighting from postings. Feel free to ignore.");
    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);

    PassageFormatter passageFormatter =
        new PassageFormatter() {
          PassageFormatter defaultFormatter = new DefaultPassageFormatter();

          @Override
          public String[] format(Passage[] passages, String content) {
            // Just turns the String snippet into a length 2
            // array of String
            return new String[] {
              "blah blah", defaultFormatter.format(passages, content).toString()
            };
          }
        };
    UnifiedHighlighter highlighter =
        UnifiedHighlighter.builder(searcher, indexAnalyzer).withFormatter(passageFormatter).build();
    Query query = new TermQuery(new Term("body", "highlighting"));
    TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
    assertEquals(1, topDocs.totalHits.value());
    int[] docIDs = new int[1];
    docIDs[0] = topDocs.scoreDocs[0].doc;
    Map<String, Object[]> snippets =
        highlighter.highlightFieldsAsObjects(new String[] {"body"}, query, docIDs, new int[] {1});
    Object[] bodySnippets = snippets.get("body");
    assertEquals(1, bodySnippets.length);
    assertTrue(
        Arrays.equals(
            new String[] {"blah blah", "Just a test <b>highlighting</b> from postings. "},
            (String[]) bodySnippets[0]));

    ir.close();
  }

  private IndexReader indexSomeFields() throws IOException {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();
    FieldType ft = new FieldType();
    ft.setIndexOptions(IndexOptions.NONE);
    ft.setTokenized(false);
    ft.setStored(true);
    ft.freeze();

    Field title = new Field("title", "", fieldType);
    Field text = new Field("text", "", fieldType);
    Field category = new Field("category", "", fieldType);

    Document doc = new Document();
    doc.add(title);
    doc.add(text);
    doc.add(category);
    title.setStringValue("This is the title field.");
    text.setStringValue("This is the text field. You can put some text if you want.");
    category.setStringValue("This is the category field.");
    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();
    return ir;
  }

  public void testFieldMatcherTermQuery() throws Exception {
    IndexReader ir = indexSomeFields();
    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighterNoFieldMatch =
        UnifiedHighlighter.builder(searcher, indexAnalyzer).withFieldMatcher(_ -> true).build();
    UnifiedHighlighter.Builder uhBuilder = new UnifiedHighlighter.Builder(searcher, indexAnalyzer);
    UnifiedHighlighter highlighterFieldMatch =
        overrideFieldMatcherForTests(randomUnifiedHighlighter(uhBuilder), null, "text");

    BooleanQuery.Builder queryBuilder =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("text", "some")), BooleanClause.Occur.SHOULD)
            .add(new TermQuery(new Term("text", "field")), BooleanClause.Occur.SHOULD)
            .add(new TermQuery(new Term("text", "this")), BooleanClause.Occur.SHOULD)
            .add(new TermQuery(new Term("title", "is")), BooleanClause.Occur.SHOULD)
            .add(new TermQuery(new Term("title", "this")), BooleanClause.Occur.SHOULD)
            .add(new TermQuery(new Term("category", "this")), BooleanClause.Occur.SHOULD)
            .add(new TermQuery(new Term("category", "some")), BooleanClause.Occur.SHOULD)
            .add(new TermQuery(new Term("category", "category")), BooleanClause.Occur.SHOULD);
    Query query = queryBuilder.build();

    // title
    {
      TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
      assertEquals(1, topDocs.totalHits.value());
      String[] snippets = highlighterNoFieldMatch.highlight("title", query, topDocs, 10);
      assertEquals(1, snippets.length);
      assertEquals("<b>This</b> <b>is</b> the title <b>field</b>.", snippets[0]);

      snippets = highlighterFieldMatch.highlight("title", query, topDocs, 10);
      assertEquals(1, snippets.length);
      assertEquals("<b>This</b> <b>is</b> the title field.", snippets[0]);

      highlighterFieldMatch =
          overrideFieldMatcherForTests(highlighterFieldMatch, "text"::equals, "text");
      snippets = highlighterFieldMatch.highlight("title", query, topDocs, 10);
      assertEquals(1, snippets.length);
      assertEquals("<b>This</b> is the title <b>field</b>.", snippets[0]);
      highlighterFieldMatch = overrideFieldMatcherForTests(highlighterFieldMatch, null, "text");
    }

    // text
    {
      TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
      assertEquals(1, topDocs.totalHits.value());
      String[] snippets = highlighterNoFieldMatch.highlight("text", query, topDocs, 10);
      assertEquals(1, snippets.length);
      assertEquals(
          "<b>This</b> <b>is</b> the text <b>field</b>. You can put <b>some</b> text if you want.",
          snippets[0]);

      snippets = highlighterFieldMatch.highlight("text", query, topDocs, 10);
      assertEquals(1, snippets.length);
      assertEquals(
          "<b>This</b> is the text <b>field</b>. You can put <b>some</b> text if you want.",
          snippets[0]);

      highlighterFieldMatch =
          overrideFieldMatcherForTests(highlighterFieldMatch, "title"::equals, "title");
      snippets = highlighterFieldMatch.highlight("text", query, topDocs, 10);
      assertEquals(1, snippets.length);
      assertEquals("<b>This</b> <b>is</b> the text field. ", snippets[0]);
      highlighterFieldMatch = overrideFieldMatcherForTests(highlighterFieldMatch, null, "title");
    }

    // category
    {
      TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
      assertEquals(1, topDocs.totalHits.value());
      String[] snippets = highlighterNoFieldMatch.highlight("category", query, topDocs, 10);
      assertEquals(1, snippets.length);
      assertEquals("<b>This</b> <b>is</b> the <b>category</b> <b>field</b>.", snippets[0]);

      snippets = highlighterFieldMatch.highlight("category", query, topDocs, 10);
      assertEquals(1, snippets.length);
      assertEquals("<b>This</b> is the <b>category</b> field.", snippets[0]);

      highlighterFieldMatch =
          overrideFieldMatcherForTests(highlighterFieldMatch, "title"::equals, "title");
      snippets = highlighterFieldMatch.highlight("category", query, topDocs, 10);
      assertEquals(1, snippets.length);
      assertEquals("<b>This</b> <b>is</b> the category field.", snippets[0]);
      highlighterFieldMatch = overrideFieldMatcherForTests(highlighterFieldMatch, null, "title");
    }
    ir.close();
  }

  public void testFieldMatcherMultiTermQuery() throws Exception {
    IndexReader ir = indexSomeFields();
    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighterNoFieldMatch =
        UnifiedHighlighter.builder(searcher, indexAnalyzer).withFieldMatcher(_ -> true).build();
    UnifiedHighlighter.Builder uhBuilder = new UnifiedHighlighter.Builder(searcher, indexAnalyzer);
    UnifiedHighlighter highlighterFieldMatch =
        overrideFieldMatcherForTests(
            randomUnifiedHighlighter(uhBuilder, EnumSet.of(HighlightFlag.MULTI_TERM_QUERY), null),
            null,
            "text");

    BooleanQuery.Builder queryBuilder =
        new BooleanQuery.Builder()
            .add(new FuzzyQuery(new Term("text", "sime"), 1), BooleanClause.Occur.SHOULD)
            .add(new PrefixQuery(new Term("text", "fie")), BooleanClause.Occur.SHOULD)
            .add(new PrefixQuery(new Term("text", "thi")), BooleanClause.Occur.SHOULD)
            .add(new TermQuery(new Term("title", "is")), BooleanClause.Occur.SHOULD)
            .add(new PrefixQuery(new Term("title", "thi")), BooleanClause.Occur.SHOULD)
            .add(new PrefixQuery(new Term("category", "thi")), BooleanClause.Occur.SHOULD)
            .add(new FuzzyQuery(new Term("category", "sime"), 1), BooleanClause.Occur.SHOULD)
            .add(new PrefixQuery(new Term("category", "categ")), BooleanClause.Occur.SHOULD);
    Query query = queryBuilder.build();

    // title
    {
      TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
      assertEquals(1, topDocs.totalHits.value());
      String[] snippets = highlighterNoFieldMatch.highlight("title", query, topDocs, 10);
      assertEquals(1, snippets.length);
      assertEquals("<b>This</b> <b>is</b> the title <b>field</b>.", snippets[0]);

      snippets = highlighterFieldMatch.highlight("title", query, topDocs, 10);
      assertEquals(1, snippets.length);
      assertEquals("<b>This</b> <b>is</b> the title field.", snippets[0]);

      highlighterFieldMatch =
          overrideFieldMatcherForTests(highlighterFieldMatch, "text"::equals, "text");
      snippets = highlighterFieldMatch.highlight("title", query, topDocs, 10);
      assertEquals(1, snippets.length);
      assertEquals("<b>This</b> is the title <b>field</b>.", snippets[0]);
      highlighterFieldMatch = overrideFieldMatcherForTests(highlighterFieldMatch, null, "text");
    }

    // text
    {
      TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
      assertEquals(1, topDocs.totalHits.value());
      String[] snippets = highlighterNoFieldMatch.highlight("text", query, topDocs, 10);
      assertEquals(1, snippets.length);
      assertEquals(
          "<b>This</b> <b>is</b> the text <b>field</b>. You can put <b>some</b> text if you want.",
          snippets[0]);

      snippets = highlighterFieldMatch.highlight("text", query, topDocs, 10);
      assertEquals(1, snippets.length);
      assertEquals(
          "<b>This</b> is the text <b>field</b>. You can put <b>some</b> text if you want.",
          snippets[0]);

      highlighterFieldMatch =
          overrideFieldMatcherForTests(highlighterFieldMatch, "title"::equals, "title");
      snippets = highlighterFieldMatch.highlight("text", query, topDocs, 10);
      assertEquals(1, snippets.length);
      assertEquals("<b>This</b> <b>is</b> the text field. ", snippets[0]);
      highlighterFieldMatch = overrideFieldMatcherForTests(highlighterFieldMatch, null, "title");
    }

    // category
    {
      TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
      assertEquals(1, topDocs.totalHits.value());
      String[] snippets = highlighterNoFieldMatch.highlight("category", query, topDocs, 10);
      assertEquals(1, snippets.length);
      assertEquals("<b>This</b> <b>is</b> the <b>category</b> <b>field</b>.", snippets[0]);

      snippets = highlighterFieldMatch.highlight("category", query, topDocs, 10);
      assertEquals(1, snippets.length);
      assertEquals("<b>This</b> is the <b>category</b> field.", snippets[0]);

      highlighterFieldMatch =
          overrideFieldMatcherForTests(highlighterFieldMatch, "title"::equals, "title");
      snippets = highlighterFieldMatch.highlight("category", query, topDocs, 10);
      assertEquals(1, snippets.length);
      assertEquals("<b>This</b> <b>is</b> the category field.", snippets[0]);
      highlighterFieldMatch = overrideFieldMatcherForTests(highlighterFieldMatch, null, "title");
    }
    ir.close();
  }

  public void testMaskedFields() throws IOException {
    final Map<String, Analyzer> fieldAnalyzers = new TreeMap<>();
    fieldAnalyzers.put("field", new WhitespaceAnalyzer());
    fieldAnalyzers.put("field_english", new EnglishAnalyzer()); // English stemming and stopwords
    fieldAnalyzers.put( // Each letter is a token
        "field_characters",
        new MockAnalyzer(random(), new CharacterRunAutomaton(new RegExp(".").toAutomaton()), true));
    fieldAnalyzers.put( // Every three letters is a token
        "field_tripples",
        new MockAnalyzer(
            random(), new CharacterRunAutomaton(new RegExp("...").toAutomaton()), true));
    Analyzer analyzer =
        new DelegatingAnalyzerWrapper(Analyzer.PER_FIELD_REUSE_STRATEGY) {
          @Override
          public Analyzer getWrappedAnalyzer(String fieldName) {
            return fieldAnalyzers.get(fieldName);
          }
        };
    FieldType fieldTypeMatched = new FieldType(fieldType);
    fieldTypeMatched.setStored(false); // matched fields don't need to be stored
    fieldTypeMatched.freeze();

    try (Directory dir = newDirectory()) {
      try (IndexWriter writer = new IndexWriter(dir, newIndexWriterConfig(analyzer))) {
        Document doc = new Document();
        doc.add(new Field("field", "dance with star", fieldType));
        doc.add(new Field("field_english", "dance with star", fieldTypeMatched));
        doc.add(new Field("field_characters", "dance with star", fieldTypeMatched));
        doc.add(new Field("field_tripples", "dance with star", fieldTypeMatched));
        writer.addDocument(doc);
      }

      try (IndexReader reader = DirectoryReader.open(dir)) {
        IndexSearcher searcher = newSearcher(reader);
        // field is highlighted based on the matches from the "field_english"
        maskedFieldsTestCase(
            analyzer,
            searcher,
            "field",
            Set.of("field_english"),
            "dancing with the stars",
            "<b>dance with star</b>",
            "<b>dance</b> with <b>star</b>");

        // field is highlighted based on the matches from the "field_characters"
        maskedFieldsTestCase(
            analyzer,
            searcher,
            "field",
            Set.of("field_characters"),
            "danc",
            "<b>danc</b>e with star",
            "<b>d</b><b>a</b><b>n</b><b>c</b>e with star");

        // field is highlighted based on the matches from the "field_tripples"
        maskedFieldsTestCase(
            analyzer,
            searcher,
            "field",
            Set.of("field_tripples"),
            "danc",
            "<b>dan</b>ce with star",
            "<b>dan</b>ce with star");

        // field is highlighted based on the matches from the "field_characters" and
        // "field_tripples"
        maskedFieldsTestCase(
            analyzer,
            searcher,
            "field",
            Set.of("field_tripples", "field_characters"),
            "danc",
            "<b>danc</b>e with star",
            "<b>dan</b><b>c</b>e with star");
      }
    }
  }

  private static void maskedFieldsTestCase(
      Analyzer analyzer,
      IndexSearcher searcher,
      String field,
      Set<String> maskedFields,
      String queryText,
      String expectedSnippetWithWeightMatches,
      String expectedSnippetWithoutWeightMatches)
      throws IOException {
    QueryBuilder queryBuilder = new QueryBuilder(analyzer);
    BooleanQuery.Builder boolQueryBuilder = new BooleanQuery.Builder();
    Query fieldPhraseQuery = queryBuilder.createPhraseQuery(field, queryText, 2);
    boolQueryBuilder.add(fieldPhraseQuery, BooleanClause.Occur.SHOULD);
    for (String maskedField : maskedFields) {
      fieldPhraseQuery = queryBuilder.createPhraseQuery(maskedField, queryText, 2);
      boolQueryBuilder.add(fieldPhraseQuery, BooleanClause.Occur.SHOULD);
    }
    Query query = boolQueryBuilder.build();
    TopDocs topDocs = searcher.search(query, 10);
    assertEquals(1, topDocs.totalHits.value());

    Function<String, Set<String>> maskedFieldsFunc =
        fieldName -> fieldName.equals(field) ? maskedFields : Collections.emptySet();
    UnifiedHighlighter.Builder uhBuilder =
        new UnifiedHighlighter.Builder(searcher, analyzer).withMaskedFieldsFunc(maskedFieldsFunc);
    UnifiedHighlighter highlighter =
        randomUnifiedHighlighter(
            uhBuilder, EnumSet.of(HighlightFlag.PHRASES), random().nextBoolean());
    String[] snippets = highlighter.highlight(field, query, topDocs, 10);
    String expectedSnippet =
        highlighter.getFlags(field).contains(HighlightFlag.WEIGHT_MATCHES)
            ? expectedSnippetWithWeightMatches
            : expectedSnippetWithoutWeightMatches;
    assertEquals(1, snippets.length);
    assertEquals(expectedSnippet, snippets[0]);
  }

  public void testMatchesSlopBug() throws IOException {
    IndexReader ir = indexSomeFields();
    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighter = UnifiedHighlighter.builder(searcher, indexAnalyzer).build();
    Query query = new PhraseQuery(2, "title", "this", "is", "the", "field");
    TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
    assertEquals(1, topDocs.totalHits.value());
    String[] snippets = highlighter.highlight("title", query, topDocs, 10);
    assertEquals(1, snippets.length);
    if (highlighter.getFlags("title").contains(HighlightFlag.WEIGHT_MATCHES)) {
      assertEquals("<b>This is the title field</b>.", snippets[0]);
    } else {
      assertEquals("<b>This</b> <b>is</b> <b>the</b> title <b>field</b>.", snippets[0]);
    }
    ir.close();
  }

  public void testFieldMatcherPhraseQuery() throws Exception {
    IndexReader ir = indexSomeFields();
    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighterNoFieldMatch =
        UnifiedHighlighter.builder(searcher, indexAnalyzer)
            // requireFieldMatch=false
            .withFieldMatcher(_ -> true)
            .build();
    UnifiedHighlighter.Builder uhBuilder = new UnifiedHighlighter.Builder(searcher, indexAnalyzer);
    UnifiedHighlighter highlighterFieldMatch =
        overrideFieldMatcherForTests(
            randomUnifiedHighlighter(
                uhBuilder, EnumSet.of(HighlightFlag.PHRASES, HighlightFlag.MULTI_TERM_QUERY), null),
            null,
            "text");

    BooleanQuery.Builder queryBuilder =
        new BooleanQuery.Builder()
            .add(new PhraseQuery("title", "this", "is", "the", "title"), BooleanClause.Occur.SHOULD)
            .add(
                new PhraseQuery(2, "category", "this", "is", "the", "field"),
                BooleanClause.Occur.SHOULD)
            .add(new PhraseQuery("text", "this", "is"), BooleanClause.Occur.SHOULD)
            .add(new PhraseQuery("category", "this", "is"), BooleanClause.Occur.SHOULD)
            .add(
                new PhraseQuery(1, "text", "you", "can", "put", "text"),
                BooleanClause.Occur.SHOULD);
    Query query = queryBuilder.build();

    // title
    {
      TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
      assertEquals(1, topDocs.totalHits.value());
      String[] snippets = highlighterNoFieldMatch.highlight("title", query, topDocs, 10);
      assertEquals(1, snippets.length);
      if (highlighterNoFieldMatch.getFlags("title").contains(HighlightFlag.WEIGHT_MATCHES)) {
        assertEquals("<b>This is the title field</b>.", snippets[0]);
      } else {
        assertEquals("<b>This</b> <b>is</b> <b>the</b> <b>title</b> <b>field</b>.", snippets[0]);
      }

      snippets = highlighterFieldMatch.highlight("title", query, topDocs, 10);
      assertEquals(1, snippets.length);
      if (highlighterFieldMatch.getFlags("title").contains(HighlightFlag.WEIGHT_MATCHES)) {
        assertEquals("<b>This is the title</b> field.", snippets[0]);
      } else {
        assertEquals("<b>This</b> <b>is</b> <b>the</b> <b>title</b> field.", snippets[0]);
      }

      highlighterFieldMatch =
          overrideFieldMatcherForTests(highlighterFieldMatch, "text"::equals, "text");
      snippets = highlighterFieldMatch.highlight("title", query, topDocs, 10);
      assertEquals(1, snippets.length);
      if (highlighterFieldMatch.getFlags("title").contains(HighlightFlag.WEIGHT_MATCHES)) {
        assertEquals("<b>This is</b> the title field.", snippets[0]);
      } else {
        assertEquals("<b>This</b> <b>is</b> the title field.", snippets[0]);
      }
      highlighterFieldMatch = overrideFieldMatcherForTests(highlighterFieldMatch, null, "text");
    }

    // text
    {
      TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
      assertEquals(1, topDocs.totalHits.value());
      String[] snippets = highlighterNoFieldMatch.highlight("text", query, topDocs, 10);
      assertEquals(1, snippets.length);
      if (highlighterNoFieldMatch.getFlags("text").contains(HighlightFlag.WEIGHT_MATCHES)) {
        assertEquals(
            "<b>This is the text field</b>. <b>You can put some text</b> if you want.",
            snippets[0]);
      } else {
        assertEquals(
            "<b>This</b> <b>is</b> <b>the</b> <b>text</b> <b>field</b>. <b>You</b> <b>can</b> <b>put</b> some <b>text</b> if you want.",
            snippets[0]);
      }

      snippets = highlighterFieldMatch.highlight("text", query, topDocs, 10);
      assertEquals(1, snippets.length);
      if (highlighterFieldMatch.getFlags("text").contains(HighlightFlag.WEIGHT_MATCHES)) {
        assertEquals(
            "<b>This is</b> the text field. <b>You can put some text</b> if you want.",
            snippets[0]);
      } else {
        // note: odd that the first "text" is highlighted.  Apparently WSTE converts PhraseQuery to
        // a SpanNearQuery with
        //   with inorder=false when non-0 slop.  Probably a bug.
        assertEquals(
            "<b>This</b> <b>is</b> the <b>text</b> field. <b>You</b> <b>can</b> <b>put</b> some <b>text</b> if you want.",
            snippets[0]);
      }

      highlighterFieldMatch =
          overrideFieldMatcherForTests(highlighterFieldMatch, "title"::equals, "title");
      snippets = highlighterFieldMatch.highlight("text", query, topDocs, 10);
      assertEquals(1, snippets.length);
      assertEquals("This is the text field. You can put some text if you want.", snippets[0]);
      highlighterFieldMatch = overrideFieldMatcherForTests(highlighterFieldMatch, null, "title");
    }

    // category
    {
      TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
      assertEquals(1, topDocs.totalHits.value());
      String[] snippets = highlighterNoFieldMatch.highlight("category", query, topDocs, 10);
      assertEquals(1, snippets.length);
      if (highlighterNoFieldMatch.getFlags("category").contains(HighlightFlag.WEIGHT_MATCHES)) {
        assertEquals("<b>This is the category field</b>.", snippets[0]);
      } else {
        assertEquals("<b>This</b> <b>is</b> <b>the</b> category <b>field</b>.", snippets[0]);
      }

      snippets = highlighterFieldMatch.highlight("category", query, topDocs, 10);
      assertEquals(1, snippets.length);
      if (highlighterFieldMatch.getFlags("category").contains(HighlightFlag.WEIGHT_MATCHES)) {
        assertEquals("<b>This is the category field</b>.", snippets[0]);
      } else {
        assertEquals("<b>This</b> <b>is</b> <b>the</b> category <b>field</b>.", snippets[0]);
      }

      highlighterFieldMatch =
          overrideFieldMatcherForTests(highlighterFieldMatch, "text"::equals, "text");
      snippets = highlighterFieldMatch.highlight("category", query, topDocs, 10);
      assertEquals(1, snippets.length);
      if (highlighterFieldMatch.getFlags("category").contains(HighlightFlag.WEIGHT_MATCHES)) {
        assertEquals("<b>This is</b> the category field.", snippets[0]);
      } else {
        assertEquals("<b>This</b> <b>is</b> the category field.", snippets[0]);
      }
      highlighterFieldMatch = overrideFieldMatcherForTests(highlighterFieldMatch, null, "text");
    }
    ir.close();
  }

  // LUCENE-7909
  public void testNestedBooleanQueryAccuracy() throws IOException {
    IndexReader ir = indexSomeFields();
    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighter =
        randomUnifiedHighlighter(
            searcher, indexAnalyzer, EnumSet.of(HighlightFlag.WEIGHT_MATCHES), true);

    // This query contains an inner Boolean of two MUST clauses (== "AND").  Only one of them is
    //  actually in the data, the other is not.  We should highlight neither.  We can highlight the
    // outer
    //  SHOULD clauses though.
    Query query =
        new BooleanQuery.Builder()
            .add(new PhraseQuery("title", "title", "field"), BooleanClause.Occur.SHOULD)
            .add(
                new BooleanQuery.Builder()
                    .add(new TermQuery(new Term("category", "category")), BooleanClause.Occur.MUST)
                    .add(
                        new TermQuery(new Term("category", "nonexistent")),
                        BooleanClause.Occur.MUST)
                    .build(),
                BooleanClause.Occur.SHOULD)
            .build();

    TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);

    String[] snippets = highlighter.highlight("title", query, topDocs);
    assertArrayEquals(new String[] {"This is the <b>title field</b>."}, snippets);

    // no highlights, not of "category" since "nonexistent" wasn't there
    snippets = highlighter.highlight("category", query, topDocs);
    assertArrayEquals(new String[] {"This is the category field."}, snippets);

    ir.close();
  }

  public void testNotReanalyzed() throws Exception {
    if (fieldType == reanalysisType) {
      return; // we're testing the *other* cases
    }

    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Field body = new Field("body", "", fieldType);
    Document doc = new Document();
    doc.add(body);

    body.setStringValue(
        "This is a test. Just a test highlighting from postings. Feel free to ignore.");
    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighter =
        randomUnifiedHighlighter(
            searcher,
            new Analyzer() {
              @Override
              protected TokenStreamComponents createComponents(String fieldName) {
                throw new AssertionError("shouldn't be called");
              }
            });
    Query query = new TermQuery(new Term("body", "highlighting"));
    TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
    assertEquals(1, topDocs.totalHits.value());
    String[] snippets = highlighter.highlight("body", query, topDocs);
    assertEquals(1, snippets.length);
    assertEquals("Just a test <b>highlighting</b> from postings. ", snippets[0]);

    ir.close();
  }

  public void testUnknownQueryWithWeightMatches() throws IOException {
    RandomIndexWriter iw = newIndexOrderPreservingWriter();

    Field body = new Field("body", "", fieldType);
    Document doc = new Document();
    doc.add(body);

    body.setStringValue("Test a one sentence document.");
    iw.addDocument(doc);

    IndexReader ir = iw.getReader();
    iw.close();

    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighter =
        randomUnifiedHighlighter(
            searcher, indexAnalyzer, EnumSet.of(HighlightFlag.WEIGHT_MATCHES), null);
    Query query =
        new BooleanQuery.Builder()
            // simple term query body:one
            .add(new TermQuery(new Term(body.name(), "one")), BooleanClause.Occur.MUST)
            // a custom query, a leaf, that which matches body:sentence
            //    Note this isn't even an MTQ.  What matters is that Weight.matches works.
            .add(
                new Query() {
                  @Override
                  public String toString(String field) {
                    return "bogus";
                  }

                  @Override
                  public Query rewrite(IndexSearcher indexSearcher) {
                    return this;
                  }

                  // we don't visit terms, and we don't expose an automata.  Thus this appears as
                  // some unknown leaf.
                  @Override
                  public void visit(QueryVisitor visitor) {
                    if (visitor.acceptField(body.name())) {
                      visitor.visitLeaf(this);
                    }
                  }

                  @Override
                  public boolean equals(Object obj) {
                    return this == obj;
                  }

                  @Override
                  public int hashCode() {
                    return System.identityHashCode(this);
                  }

                  @Override
                  public Weight createWeight(
                      IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
                    // TODO maybe should loop through index terms to show we can see other terms
                    return new TermQuery(new Term(body.name(), "sentence"))
                        .createWeight(searcher, scoreMode, boost);
                  }
                },
                BooleanClause.Occur.MUST)
            .build();
    TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);
    assertEquals(1, topDocs.totalHits.value());
    String[] snippets = highlighter.highlight("body", query, topDocs);
    assertEquals(1, snippets.length);
    assertEquals("Test a <b>one</b> <b>sentence</b> document.", snippets[0]);

    ir.close();
  }

  public void testQueryWithLongTerm() throws IOException {
    IndexReader ir = indexSomeFields();
    IndexSearcher searcher = newSearcher(ir);
    UnifiedHighlighter highlighter =
        randomUnifiedHighlighter(
            searcher, indexAnalyzer, EnumSet.of(HighlightFlag.WEIGHT_MATCHES), true);

    Query query =
        new BooleanQuery.Builder()
            .add(
                new TermQuery(new Term("title", "a".repeat(Automata.MAX_STRING_UNION_TERM_LENGTH))),
                BooleanClause.Occur.SHOULD)
            .add(
                new TermQuery(
                    new Term("title", "a".repeat(Automata.MAX_STRING_UNION_TERM_LENGTH + 1))),
                BooleanClause.Occur.SHOULD)
            .add(new TermQuery(new Term("title", "title")), BooleanClause.Occur.SHOULD)
            .build();

    TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER);

    String[] snippets = highlighter.highlight("title", query, topDocs);
    assertArrayEquals(new String[] {"This is the <b>title</b> field."}, snippets);

    ir.close();
  }
}
