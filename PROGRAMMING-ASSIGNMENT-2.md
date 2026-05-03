# PA2: USE PYTHON, as it will be much easier to debug the code and check the code when coding. We will also have to evaluate the queries, and the python notebook will make this much easier.

Program SYSTEMATICALLY, firstly do the 1., then write everything we have done into the PA2 report. Then do the 2. and create relevant sections in the PA2 report yet again. This way we won't forget what we did at the end, when we will be writing the report.

Firstly create the directory files, as are necessary for the assignment. Extract the PA2 into a notebook, so that the LLM has context if it wants to check if it is relevant for the programming assignment 2.

All the code that we should use are already in the notebooks, provided to us.

## SEM VPRAŠAL ASISTENTKO:

mi imamo enostavno z repositoriji, ker imamo predvidljivo strukturo. Če imamo res samo repositorije, kjer imamo README notri, lahko samo en query za querijat HTML kodo ven. Če imamo še ISSUES in ostale page, pa imajo drugačno strukturo HTML elementi, aka za te pages pa drugače queriat relevantni infor ven. Ampak mi smo res omejeni samo na repositorije, tok sem spucal database.

Se pravi dobimo readme ven iz HTML strani, vn vržemo slike in ostali crap, res samo paragrafe in to ohranimo. Lahko probamo per paragraph chunckat/segmentirati tekst. Ampak lahko pride do problema, da se bodo modeli za embedding pritožili, če bodo preveliki segmenti, aka preveliki paragrafi. To poglej, da niso kkšni paragrafi ful dolgi, in maga implementirej per paragraph, se pravi je vsak paragraf svoj segment, ali pa več paragrafov en segment, da bo query nam retrieval več informacije. Potem pa omeji, če je zelo velik paragraf, da ga razdeli na dva dela. To premisli, kolk bi skupaj zlepli teksta v en chunk, da ko bomo spraševali z queriji, da ne bo vrnil samo pet besed, ker je bil en paragraf samo kot ime slike, hkrati pa da ne bo preveč teksta, ker ni relevanten cel tekst za query. Jaz bi šel tako, da imamo nek limit, ki je odvisen od modela, ki ga uporabljamo za računanje embeddingov, in potem skupaj lepi paragrafe, da pridemo do tega limita, če limit ni presežen če naslednji paragraf dodamo, ga dodaj, če ga presežemo, pa to kar si do sedaj zlepil predtavlja en segment. Edge case za rezanje enega paragrafa v več delov pa je, če je segment prazen in če dodamo trenutni paragraf notr že presežemo limito, potem pomeni, da je ta paragraf že sam po sebi večji od limite, zato ta paragraf razdelimo na pol npr., in iz vsake polovice naredimo svoj segment. Oziroma delimo dolžino paragrafa z max tokeni, ki gredo v embedding model, da ugotovimo na kolk delov rabimo razdelit paragram, ker je mogoče celo večji kot za samo 2 segmenta in bi rabili paragraf razdeliti v 3 segmente, da ga požre embedding model.

## ## 1. extract relevant content

we extract the headers, the paragraphs... but throw out any uneccessary information, such as menus...

Because we have the github repositories, we should retrieve the information in the markdown file, aka the part that we see on the webpage as the README.md part. Paste one of the fetched HTML files into claude and ask him where any relevant information is stored. Aka how to get to the README.md part of the page, that is the only part that we are interested in. Then check on the database, if it is working correctly. It is easy for us, because each page has the relevant information in the same place, only the part, that is rendering the README.md file text.

**Include a brief description** of the criteria in the PA2 report. After implementing this in the python notebook write a section in the PA2 report, to not forget this information!

Because we crawled the Github repostiories regarding the image segmentation, we should retrieve only the [README.md](http://README.md) rendered part from the HTML. 

## ## 2. segment the content from 1.

...In the vaje4_vector_db_sample.ipynb.

Define how we will segment the text into chunks: dynamic or static? Apply this segmentation strategy and apply the vector representation. Be veary and check the vector representations, if there are too many zeroes! The segments could be too sparse?

Keep in mind that we will be asking "what is the best model to segment biology images", the same questions we would be using ChatGPT. So we should segment the data in a way that will be good for us, when we will be feeding a large language model with the relevant information. Not too large, so that the context would fill up too quickly, but not too little, so that the large language model would not have enough context.

Create strategies to structure the text, and then test how this affects the query. So these three parts, 2., 3. and 4. should be worked on iteratively. Test different segmentation strategies, of how to divide text, create a new table with vecor representation and test if queries are working better.

## ## 3. calculate the vector representation

the vector representations of segments and store these segments into the pgvector database.

Create the new databases and store the segmentations into the database.

## ## 4. query the database

ask different questions when focusing on the query, so that we see what queries are bad and what are good. This is connected with what data we actually have. I guess that query questions regarding wat library to use to segment roads will give good results, whereas questions regarding how segmentation works are not so good.

## ## 5. implement the reranking strategies for the query step (4.)

If we get the best 5 results, it doesn't neccessary mean that the result that is the closest to the query isn't neccessarily the best result. This is why we rerank the best 5 results again using a cross encoder model.

We feed the cross encoder model with the original query and another imput parameter are the query results. We obtain the scores for each of these queries, measure it against the db results, and rerank these segments based on the score.

# PROGRAMMING ASSIGNMENT 2 (on fri e-učilnica) notes:

1. Identifying and extracting important information
2. Storing information to a vector database

create new tables to store the vector representation

1. Retrieving information relevant to the query

firstly we will query the database, and in the fist part we will have to evaluate the quality of the results. So how the best 5 results, best 3 results match our query. We will see that some results will be good, and some will not be so good.

So for the bad query results we will have to implement reranking, that will improve the ranking.

## Using XPath

XPath enables us to navigate XML and HTML. So it is like a query to get the elements, that have the relevant information for us.

For example: "/html/body/div — selects a div element that is a child of body, which is a child of html" is the sintax to get the div inside html->body

Put one HTML from our database (the repository HTML) into the Claude and the XPath examples from the učilnica, to construct the XPath query to get the relevant information from the HTML.

OR we could use regular expresions, to extract text from our fetched HTML pages.

## The PA3 will look like this:

We will implement RAG (retrieval augmented generation), which will have 2 working modes:

1. without context
2. with CONTEXT OBRATINED FROM VECTOR DATABASE - when asking a question, we will obtain relevant information from our database and add it to the context.

We will then look how the quality of the answers gets better, if we input the model with aditional context from the retrieved database.

## 3.1 Retrieved content

The retrieved text should be put in the "cleaned_content" under the page table. Also create an entirely new table named page_segment, with 4 atributes: id, page_id, page_segment, and embedding. There is already a SQL segment, that defines all of this. If we will use a different strategy, we could use different atributes, and different tables

## 3.2 Try different embedding models

start with the ones, that we used in the lab sessions. We already have the code provided for the implementation. Copy the code, and test it on our data, to see how it is working for us. Then diviate and test other models, that could be better for our Github use case. 

Because we have english text, we do not have to use MULTILINGUAL models, as usually models are trained on english text, so they are native in english by default.

If we want to check what other models we could use, use [this link](https://www.sbert.net/docs/sentence_transformer/pretrained_models.html). This link is found in the "sentence-transformers" in the učilnica. When clicking on the link scroll down until you see the "**Embedding Models:**" -> "What Sentence Transformer **models** can I use?". This is where we have the list of all the available models.

Or we could search for embedding transformers on the [Hugging Face models](https://huggingface.co/models?pipeline_tag=sentence-similarity&library=sentence-transformers&language=en&sort=trending):

1. Tasks -> NATURAL LANGUAGE PROCESSING submenu (or just search and select the "sentence similarity")
2. Libraries -> sentence-tranformers
3. Languages -> English

## 3.3 Querying the database

as shown in the vector_db_sample.ipynb  in the Example 1: we should query the databaase as such:

Our task is to find 3 queries, for which good results are returned, and 3 queries, for which bad results are returned.

## 4.1 Reranking

We will see for some examples, that eventhough we got 5 best results, they are not ranked the best. As such we should rerank them and OBSERVE how the ranking is changed for the best results that get returned.

## 6.1 WHAT TO INCLUDE IN THE REPORT - !!IMPORTANT!!:

Create a report with these subsections, and fill them out when doing the programming for the assignment.

# NOTEBOOKS (assignment-2-notebooks) notes:

## vaje4_vector_db_sample.ipynb

we use the database schema from our first assignemnt - pgvector/pgvector:pg17. If this was not the schema we used in the first assignment, we will have to migrate the database. We used this database, so it is ok.

Run the part of the code "CREATE EXTENSION IF NOT EXISTS vector", to create an extension of the database, in which we will store the embedings.

Instead of retrieval of articles from Wikipedia, we will extract the content using BeautifulSoup or any other libraries, from our fetched database. This extracted content is used to calculate the vector representation:

- ### 1st strategy:

each text segment includes 50 characters & multilingual embedding model LaBSE is used.

- ### 2nd strategy:

each "paragraph-based" segmentation. Each paragraph is a chunk.

use the link in the notebook, to define the strategy of how we should divided the text! Do we use simple static split, 50 tokens, or do we use a more complex strategy that divides the text in dynamic chunks. [This is the article](https://www.analyticsvidhya.com/blog/2024/10/chunking-techniques-to-build-exceptional-rag-systems/).

### Querying

To speed up the querying in the vector based database, we must add relevant indexes. So instead of reading the whole database each time for each vector, we should create an index, to help with the querying. IVFFlat or HSNW index should be used.

On [this link](https://github.com/pgvector/pgvector?tab=readme-ov-file#indexing) you have different distances that you can use when creating the index.

conn.execute('CREATE INDEX ON showcase.wiki_chunks_fixed_length USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);')

### Defining good queryes

In the scope of the assignment, we will have to find 3 queries that yeld good results, and 3 queries that do not yeld good results.

### To improve the results

than after having the queries, we will add a rerank function, that will rerank the candidates. In the notebook "ms-marco-MiniLM-L6-v2" is used to rerank the query results that we got. This will usually improve the rankings of the best candidates, that we got previously. So instead of 1., 2., 3., we could get 2., 1., 3. rankings after using the rearanking function.

IF WE ARE DEALING WITH SLOVENE LANGUAGE, MAKE SURE TO USE A MULTILINGUAL MODEL TO RERANK, SO IT WILL WORK BETTER. We don't have this problem, but check if we have any language other than english in our repositories.

So for SLOVENE TEXT:
LaBSE -> embedding calculation
reranking -> use multilingual cross-encoder model.

# Vaje 5 (14.4.2026) - vaje5_RoadRunner.ipynb - this is an advance technique of how to find relevant data on different webpages. We don't have to use this, because we know our layout, as only README parts of github repositories will be used. We can use simpler regex or beautiful soup to get the relevant data and content from our webpages. - NOT RELEVANT FOR US.

This knowledge can be used for extracting the data out of HTML.

```html
<html>
  <body>
    <h1> Title </h1>
    <p> Paragraph </p>
  </body>
</html>
```

The RoadRunner will create a node structure as:

```
html -> body -> h1
            \-> p
```

### Why is this useful??

We can use this wrapper - a generalized tree structure, to extract the content from simmilar web pages! If web page 1 and web page 2 have similar layout, we can extract the data from both websites using the same wrapper. Aka if website 1 has a menu on top, side menu on the left and the article (content) in the main view, and website 2 has the same layout (a menu a side menu and the main article), we can use the same wrapper from website 1 and use it on website 2 to extract the main content.

### The main idea:

to extract the html as dom tree using beautiful soup, and to generate a simplified representation (?)

### Example: Parsing Simple HTML Pages

we have two example html elements: html1 and html2. They look like this

```html
<html> <-- html1
  <body>
    <div>
      <h1> Title A </h1>
      <p> Item 1 </p>
      <p> Item 2 </p>
    </div>
  </body>
</html>
```

```html
<html> <-- html2
  <body>
    <div>
      <h1> Title B </h1>
      <p> Item 3 </p>
      <p> Item 4 </p>
    </div>
  </body>
</html>
```

The htmls are different in content and NOT in structure.

We use html_to_tree to map the html into a tree representation, which is printed as terminal output in the notebook (the vaje5_RoadRunner.ipynb notebook). This way we go from raw html, aka text into a tree representation, which is structured data in our computer, and we can traverse this tree (go to parent, see childrens...). This would be MUCH slower and harder if done on the raw text/html.

### Step 2: Tree Alignment (generalization)

When we are extracting meaningful information, we look at nodes, that are aligned in both pages, but have different contents. If two noted have same contents, for example the menu on both pages, this is not meaningfull. But the artcile will match in nodes (a  inside  and  for example) but the content will be different. This tells us that this information is NOT static, and is relevant for us for this website. It tells us something new, because it is different on both webpages.

If data does not match, so this means it is relevant, we tag it with #PCDATA. This tells us that we should extract this data.

If we draw comparisons with the previous assignment, we can use this advanced approach to get relevant data out of the website. BUT because we know the webpages, we don't have to use this advance approach, as we know the structure of our data. We only have github repositories, which we know that we want only the readme part of the webpage.

### Eample: Generalizing two trees

For our example of html1 and html2 this would generate #PCDATA as this:

```html
<html> <-- this is our wrapper/ our template, that we can use on webpages to extract the data
  <body>
    <div>
      <h1> #PCDATA </h1> <-- Title A (in html2) != Title B (in html2) => relevant info, so #PCDATA
      <p> #PCDATA </p> <-- Item 1 != Item 3 => relevant info, because it differs, so #PCDATA
      <p> #PCDATA </p> <-- Item 2 != Item 4 => relevant info, so #PCDATA
    </div>
  </body>
</html>
```

We have now generated a wrapper that can be used on webpages to get relevant into. This was generated on differences between two webpages, that got alligned, but this wrapper can be used on a random 3rd html website, and would get relevant info from it.

### Step 3: More complex webpages in reality

Because in reality the webpages are more complex, for example on webpage has additional image and the other has an aditional paragraph, lets look how this will work.

### Example: Aligning Pages with repetition and optional elements

Our html files now differ by one image and one more paragraph

```html
<html> <-- html1
  <body>
    <div>
      <h1> Title A </h1>
      <img src="img.jpg"/> <-- aditional image in html1
      <p> Item 1 </p>
      <p> Item 2 </p>
    </div>
  </body>
</html>
```

```html
<html> <-- html2
  <body>
    <div>
      <h1> Title B </h1>
      <p> Item 3 </p>
      <p> Item 4 </p>
      <p> Item 5 </p> <-- aditional paragraph in html2
    </div>
  </body>
</html>
```

We firstly generate dom tree representations of the raw html text for html1 and html2 and generate a wrapper from them by aligning the nodes and checking whether the content is different, in which case that node gets a #PCDATA.

#### WHAT IS DIFFERENT than the easy example is that the image and the second paragraph is added to the wrapper, because this content is different in both pages, aka it is relevant information for us. If image is on one webpage and not on the other, this is relevant data, it provides information to us.

### Summary

from previous labs, the vaje_4, where we processed the whole document as a text, in todays lab we firstly generated a tree structure from the html document, instead of processing it as a text. Then we alligned the multiple trees, and generated a wrapper as a generalization. Then we generated a new webpage and applied the wrapper on the new webpage to get relevant data from it.

---

# # OFFICIAL PROGRAMMING ASSIGNMENT 2 INSTRUCTONS

## 1. Introduction
The goal of this programming assignment is to extract useful information from the crawled websites and store it in a vector database for answering domain-specific questions. The assignment consists of three parts:

1. Identifying and extracting important information
2. Storing information to a vector database
3. Retrieving information relevant to the query

## 2. Identifying and extracting information

In this part of the assignment, your task is to identify meaningful content (such as article titles, product names, descriptions, prices, or publication dates) from HTML documents and extract it in a structured format. The goal is to prepare high-quality data that can later be stored in a vector database and queried efficiently.

You will explore two main techniques: XPath expressions and regular expressions.

## 2.1. Using XPath expressions

One approach to navigate XML and HTML files is to use XPath expressions. XPath (XML Path Language) is a syntax for navigating through elements and attributes in an XML/HTML document tree.

XPath allows you to:

- Select nodes or node-sets in an XML document
- Navigate based on element names, attribute values, and hierarchy
- Perform conditional searches using predicates

**Basic XPath Syntax:**

- `/html/body/div` — selects a `div` element that is a child of `body`, which is a child of `html`
- `//p` — selects all `<p>` elements in the document
- `//div[@class="content"]` — selects all `<div>` elements with a class attribute equal to “content”
- `//a[@href]` — selects all anchor tags with an `href` attribute

**Python Example Using XPath:**

```html
from lxml import html

html_content = """
<html>
  <body>
    <div class="article">
      <h2>Sample Article</h2>
      <p>This is a test paragraph.</p>
    </div>
  </body>
</html>
"""
tree = html.fromstring(html_content)
title = tree.xpath('//div[@class="article"]/h2/text()')
print(title)  # Output: ['Sample Article']
```


## 2.2. Using regular expressions

Another approach to content extraction is using **regular expressions** (regex). Regular expressions are powerful tools for pattern matching and text processing. While regex can be faster for small and well-structured tasks, it is more error-prone for messy or deeply nested HTML. Use it when you know the structure of the text you’re working with and want to quickly extract specific elements.


Basic Regex Syntax:

- `.` — matches any character except newline
- `*` — matches 0 or more repetitions
- `+` — matches 1 or more repetitions
- `?` — makes the preceding token optional
- `[]` — matches any one character in the brackets
- `()` — groups expressions
- `\d` — matches any digit
- `\s` — matches whitespace
- `^` — matches the start of a string
- `$` — matches the end of a string

**Common Regex Examples and What They Match:**


| Regular Expression	| Matches |
| ------- | ------- |
| `<h2>(.*?)</h2>`	    | The text inside an `<h2>` tag |
| `href="(.*?)"`	      | The value of an `href` attribute |
| `<p>(.*?)</p>`	      | Paragraph content between `<p>` tags |
| `\d{4}-\d{2}-\d{2}`	  | Dates in `YYYY-MM-DD` format |
| `\$[0-9]+\.[0-9]{2}`	| Dollar amounts like `$19.99` |
| `<[^>]+>`	            | Any HTML tag |

You can test your regular expressions expressions at [regexr.com](https://regexr.com).

**Python Example Using Regular Expressions:**

```html
import re

html_content = """
<html>
  <body>
    <div class="article">
      <h2>Sample Article</h2>    
      <p>Published on 2024-12-05</p>
      <p>Price: $19.99</p>
      <a href="https://example.com">Read more</a>
    </div>
  </body>
</html>
"""

# Extract article title
title = re.search(r'<h2>(.*?)</h2>', html_content)
print(title.group(1))  # Output: Sample Article

# Extract date
date = re.search(r'\d{4}-\d{2}-\d{2}', html_content)
print(date.group(0))  # Output: 2024-12-05

# Extract price
price = re.search(r'\$[0-9]+\.[0-9]{2}', html_content)
print(price.group(0))  # Output: $19.99

# Extract link
link = re.search(r'href="(.*?)"', html_content)
print(link.group(1))  # Output: https://example.com
```

Regular expressions can be concise and effective for small-scale, well-formatted content. However, for more complex HTML, tools like XPath (or HTML parsers like BeautifulSoup) are generally more reliable.

## 2.3. Your task

You should use XPath and Regular expressions to filter relevant 5000 HTML websites you gathered in PA1.

Prepare a plain text version of the website where you remove static elements like website headers, menus, footers, etc., while keeping the page’s content.
Define criteria for splitting the page into multiple thematic sections. Some suggestions include using paragraphs, sentences, page sections, or any other indicators, with the goal of each section being a complete description of one topic. Include a brief description of the criteria in the PA2 report.
You have to select the most appropriate strategy for dividing text and list its advantages and disadvantages in the PA2 report. It is often necessary to conduct some testing to determine what works best. Without a proper approach, you risk overlooking important information or providing incomplete or out-of-context retrieved segments when querying the vector database. Some of the key factors to consider when dividing the text into smaller portions are the size of segments and context preservation.

## 3. Storing extracted information in a vector database

The next step of the assignment is to calculate embeddings for text segments and store the extracted information in a **vector database**. For this, you will expand the database you created in PA1 with a new table that stores **document segments** and their **vector representations**.

For a more streamlined start, you can utilize the following [sample](assignment-2-notebooks/vaje4_vector_db_sample.ipynb).

## 3.1 Preparing the database

Make sure you are using a [pgvector database](https://github.com/pgvector/pgvector). Pgvector is a PostgreSQL extension that adds support for storing and indexing vector data. You can create a new pgvector database using the following command:

```bash
docker run --name postgresql-wier \
 -e POSTGRES_PASSWORD=SecretPassword \
 -e POSTGRES_USER=user \
 -e POSTGRES_DB=wier \
 -v $PWD/pgdata:/var/lib/postgresql/data \
 -v $PWD/init-scripts:/docker-entrypoint-initdb.d \
 -p 5432:5432 \
 -d pgvector/pgvector:pg17
```

In your database, enable the `vector` extension using the statement:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

Once you have the database set up, add a new column to the `page` table to store `cleaned plain text content`, and create a new table to store `page segments` and their `vector embeddings`.

[SCHEMA IMAGE, SHOWING HOW TO EXTEND OUR DATABASE](vector-db-schema.png)

```sql
ALTER TABLE crawl_db.page ADD COLUMN cleaned_content TEXT;

CREATE TABLE crawl_db.page_segment (
  id serial NOT NULL,
  page_id integer,
  page_segment TEXT,
  embedding vector(768)
);

ALTER TABLE crawl_db.page_segment
  ADD CONSTRAINT fk_page_page_segment
  FOREIGN KEY (page_id)
  REFERENCES crawl_db.page(id)
  ON DELETE RESTRICT;
```

You are encouraged to extend the database schema with additional metadata fields that may be useful during retrieval — for example, the HTML tag, section title, heading level, or local context of a segment. If you do, describe these design decisions in your report.

## 3.2 Generate vector embeddings

The next step is to generate **vector embeddings** for each page segment. Embeddings are fixed-length vector representations of text that capture its semantic meaning.

You should experiment with **different embedding models**, such as:

- `all-MiniLM-L6-v2` (English) or `LaBSE` (Multilingual) from [sentence-transformers](https://www.sbert.net)
- OpenAI’s `text-embedding-ada-002`
- Hugging Face Transformers like `distilbert-base uncased` (English), `SloBERETa` (Slovene), `CroSloEngualBERT` (multilingual)
- Any domain-specific models relevant to your dataset (e.g. medical)

## 3.3 Querying the database

By default, pgvector performs an exact search when querying the database. Adding [index](https://github.com/pgvector/pgvector?tab=readme-ov-file#indexing) enables us to use approximate nearest neighbour (ANN) search, which takes less time when compared to exact search. The pgvector supports two indexes, HSNW and IVFFlat. Use one of them in your assignment.

Queries and segments that are similar will map to similar embeddings. By comparing a query embedding to the segment embeddings, you can retrieve the most relevant content based on the chosen metric, such as cosine distance, L1 distance, or inner product..

You can generate embeddings using Python libraries like `sentence-transformers`, `transformers`, or `openai`.

For each segment, compute its embedding and store it in the `embedding` column of the `page_segment` table.

## 4. Information retrieval

In this part of the assignment, you will create a simple program that takes a **query** (for example, a user question) and retrieves the most relevant text segments from your database using **vector similarity search**.

You can use similarity operators provided by pgvector, such as:

- `<->` L2 distance (lower is more similar)
- `<=>` Cosine distance (lower is more similar)
- `<+>` L1 distance (lower is more similar)

**Your task is to experiment** with:

- Different **similarity metrics**
- Different **embedding models**
- Including or excluding **segment metadata** in your embeddings (e.g., including the title or section tag)
- **Chunking strategies** for segmenting the page (e.g., fixed-length? by paragraph? by heading?)

The goal is to find the **best combination for your domain**, so that given a query, your retriever consistently returns the most useful results.

You should implement a small Python demo program that:

1. Accepts a user query
2. Computes its embedding
3. Performs a similarity search over the segment embeddings in your database
4. Returns and displays the top-k most relevant segments

In the demo program, include three queries that return relevant results for your domain, and prepare at three queries that does not return the expected result.

## 4.1 Reranking 

Even with good embeddings, the first top‑k results from vector similarity search are not always the most relevant. To improve final accuracy, add a **reranking step**, which takes the initial retrieved segments (e.g., top‑k) and reorders them using a more precise relevance model. Test reranking step with the three queries that do not return the expected result.

**Why reranking?**

- Embedding search provides fast approximate relevance
- Some segments may be related but not true answers
- Reranking uses a stronger model to score how well each segment answers the query

**How reranking works**

1. Compute query embedding
2. Retrieve top‑k segments from pgvector
3. Pass each segment together with the query to a reranker model (e.g., a cross‑encoder)
4. Sort by relevance score and return the final top‑k results
5. Typical rerankers include cross‑encoder models from sentence‑transformers. You can find the example in the notebook in the next section. Choose the model based on your dataset language.


## 5 Basic tools

Utilize the following Jupyter notebooks for a more streamlined start:

- [Introductuctory sample to vector databases](assignment-2-notebooks/vaje4_introduction_to_vector_databases.ipynb)
- [Sample for the pgvector database](assignment-2-notebooks/vaje4_vector_db_sample.ipynb)

## 6 Submission instructions

Publish your work into the **same repository** as you used for the first assignment. The repository must comply with the following structure:

```
pa2/
├── report-extraction.pdf           # PDF report with description and evaluation
├── README.md                       # Setup instructions for running the code as well as 
                                    # detailed instructions for restoring your exported
                                    # database file in pgAdmin
├── implementation-extraction/      # Your implementation code (well documented)
│   └── demo.py                     # A demo script that allows us to test the retriever
├── extraction-db/                  # Your database including extracted segments and their embeddings
```

## 6.1 What to include in the report

Your report should include the following:

- Information about how you performed website filtering.
- Approach to dividing the text, including criteria for splitting the page into multiple thematic sections, the implemented strategy for dividing text, and advantages and disadvantages of the implemented strategy.
- What embeddings did you decide to use, and why
- What similarity metric did you decide to use, and why
- Examples of queries and your retriever’s responses
- Which reranker model you used and example queries showing improvement
- One example where reranking still fails
- Limitations of your document retriever (e.g. examples of queries with inadequate responses)

**Make sure to also describe methods you tried but decided not to use.**

## 6.2 What to submit

Please submit one .txt file with a link to the **private** GitHub repository that contains all required files for Programming Assignment 2 (PA2) according to the above instructions.

Only one of the group members may submit this assignment. It is of utmost importance that you do not forget to add user opbieps to the GitHub repository with at least read credentials. 

**There will be a penalty of 10% per late day for late submissions**. It is possible to submit PA2 up to 3 days after the assignment deadline. **All submissions will be subject to a plagiarism check**. Groups that copy the results or code from the others will be graded with zero points.