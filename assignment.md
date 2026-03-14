# Programming Assignment 1

## 1. Introduction
The goal of this programming assignment is to build a standalone preferential crawler that will crawl websites within your chosen domain. The crawler will roughly consist of the following components:

- HTTP downloader and renderer: retrieves and renders web pages.
- Data extractor: extracts images and hyperlinks.
- Duplicate detector: detects already‑parsed pages.
- URL frontier: maintains a list of URLs to be parsed.
- Datastore: stores data and metadata used by the crawler.

Description of the image of the crawler architecture, we are to follow is in the `images/description-crawler-architecture.md` file.

## 2. Instructions
Implement a preferential web crawler that crawls specific websites within your chosen domain using a programming language of your choice.

Select a domain you will explore in all subsequent project assignments. Below are some domains that could be interesting:

Slovenian news: target selected articles of Slovenian news provider, such as RTV SLO, 24ur, or Slovenske novice, focusing on topic(s) of your choice like politics, environment, sports, local events, etc. 
Workaway: explore global volunteering opportunities by crawling the platform workaway.info that connects travellers with hosts worldwide. Targets only selected regions, project types, high‑rated hosts or criteria of your choice. 
Med.Over.Net: Gather useful advice for selected topics by crawling Med.Over.Net. Med.Over.Net is a Slovenian online discussion platform where people share health‑related questions and experiences and also read health‑related articles.
GitHub: focus on a subset of GitHub, such as repositories under a specific topic of your choice (e.g., machine-learning). The goal is not to clone or analyze full repositories, but to extract textual and structured information that is safe and ethical to crawl—such as README files, documentation pages, project descriptions, or issues.
Other domains: possible upon prior approval.
The crawler needs to be implemented with multiple workers that retrieve different web pages in parallel. The number of workers should be a parameter when starting the crawler. The frontier strategy needs to follow the preferential strategy. In the report explain how your strategy is implemented.

Check and respect the [robots.txt](https://en.wikipedia.org/wiki/Robots.txt) file for each domain if it exists. Correctly respect the commands User-agent, Allow, Disallow, Crawl-delay and Sitemap. Make sure to respect robots.txt as sites that define special crawling rules often contain [spider traps](https://en.wikipedia.org/wiki/Spider_trap). Also make sure that you follow ethics and do not send request to the same server more often than one request in 5 seconds (not only domain but also IP!).

In a database store canonicalized URLs only!

During crawling you need to detect duplicate web pages. The easiest solution is to check whether a web page with the same page content was already parsed (hint: you can extend the database with a hash, otherwise you need compare exact HTML code). If your crawler gets a URL from a frontier that has already been parsed, this is not treated as a duplicate. In such cases there is no need to re-crawl the page, just add a record into to the table link accordingly.

BONUS POINTS: Deduplication using exact match is not efficient as some minor content can be different but two web pages can still be the same. Implement one of the [Locality-sensitive hashing](https://en.wikipedia.org/wiki/Locality-sensitive_hashing) methods to find collisions and then apply Jaccard distance (e.g. using unigrams) to detect a possible duplicate. Also, select parameters for this method. Document your implementation and include an example of duplicate detection in the report. Note, you need to implement the method yourself to get bonus points.

When your crawler fetches and renders a web page, do some simple parsing to detect images and next links.

- When parsing links, include links from href attributes and onclick Javascript events (e.g. location.href or document.location). Be careful to correctly extend the relative URLs before adding them to the frontier.
- Detect images on a web page only based on img tag, where the src attribute points to an image URL.
- Implement a strategy for preferential crawling. Detect the relevance of each link to your domain and crawl more relevant links first. This will also help with your subsequent programming assignments as you will have more relavant pages to work with.

Donwload HTML content only (and PDF where required for the domain). List all other content (.doc, .docx, .ppt and .pptx) in the page_data table - there is no need to populate data field (i.e. binary content). In case you put a link into a frontier and identify content as a binary source, you can just set its page_type to BINARY. The same holds for the image data.

In your crawler implementation you can use libraries that implement headless browsers but not libraries that already implement web crawler functionality. Therefore, some useful libraries that you can use are:

- [HTML cleaner](http://htmlcleaner.sourceforge.net/)
- [HTML parser](http://htmlparser.sourceforge.net/)
- [JSoup](https://jsoup.org/)
- [Jaunt API](https://jaunt-api.com/)
- [HTTP client](http://hc.apache.org/)
- [Selenium](https://www.seleniumhq.org/)
- [phantomJS](http://phantomjs.org/)
- [HTMLUnit](http://htmlunit.sourceforge.net/)
- etc.

On the other hand, you <b>MUST NOT</b> use libraries like the following:

- [Scrapy](https://scrapy.org/)
- [Apache Nuch](https://nutch.apache.org/)
- [crawler4j](https://github.com/yasserg/crawler4j)
- [gecco](https://github.com/xtuhcy/gecco)
- [Norconex HTTP Collector](https://www.norconex.com/collectors/collector-http/)
- [webmagic](https://github.com/code4craft/webmagic)
- [Webmuncher](https://github.com/dadepo/Webmuncher)

To make sure that you correctly gather all the needed content placed into the DOM by Javascript, you should use headless browsers. Googlebot implements this as a two-step process or expects to retrieve dynamically built web page from an HTTP server. A nice session on crawling modern web sites built using JS frameworks, link parsing and image indexing was a part of Google IO 2018 and it is suggested for you to check it. The transcription of the video can be found in `additional-resources/assignment.md`.



In your implementation you must set the User-Agent field of your bot to `fri-wier-IEPS-TOLPA`.

## 2.1 Crawldb design

Below there is a model of a crawldb database that your crawler needs to use. This is just a base model, which you MUST NOT change, but you can extend it with additional fields, tables, … that your crawler might need. You should use PostgreSQL database and create a schema using a prepared SQL script `crawldb.sql`.

Table site contains web site specific data. Each site can contain multiple web pages - table page. Populate all the fields accordingly when parsing. If a page is of type HTML, its content should be stored as a value within html_content attribute, otherwise (if crawler detects a binary file - e.g. .doc), html_content is set to NULL and a record in the page_data table is created. Available page type codes are HTML, BINARY, DUPLICATE and FRONTIER. The duplicate page should not have set the html_content value and should be linked to a duplicate version of a page.

You can optionally use table page also as a current frontier queue storage.

## 3. Basic tools

Suggested environment setup (Anaconda): - we will be using 

conda create -n wier python=3.10
conda activate wier
conda install selenium psycopg2 nb_conda requests
conda install -c anaconda flask pyopenssl
conda install -c conda-forge flask-httpauth
jupyter notebook
Use setuptools 81.0.0 to prevent compatibility issues with nb_conda.

During the lab sessions in the first two weeks of the term, we will introduce basic tools for students who are less experienced with web scraping and database access, and additional examples will be gradually added to the list during this period.

- Jupyter notebook tutorial Web crawling - basic tools that introduces the basic tools to start working on the assignment. You can find this notebook below. This can be found in the `"vaje-1 Web crawling - basic tools.ipynb"`
- A showcase of server (Remote crawler database (server)) found in the `"vaje-2 Remote crawler database (server).ipynb"` and client (Remote crawler database (client)) - found in the `"vaje-2 Remote crawler database (client).ipynb"` notebook - implementation in case you would like to run multiple crawlers (e.g. from each group member homes) and have the same crawler database. You can find both notebooks below.
- Jupiter notebook tutorial on Preferential crawling. You can find this in the `"vaje-2-Preferential-crawling.ipynb"`



## 4. What to include in the report
The report should follow the [standard structure](https://fri.uni-lj.si/sl/napotki-za-pisanje-porocila). It should not exceed 3-4 pages. You can include extra pages if you need them for visualisations of the database or large tables.

In the report include the following:

All the specifics and decisions you make based on the instructions above and describe the implementation of your preferential crawler. Describe how you implemented a strategy for preferential crawling.
Document also parameters that are needed for your crawler, specifics, problems that you had during the development and solutions.
For the sites that are given in the instructions’ seed list and also for the whole crawldb together (for both separately) report general statistics of crawldb (number of sites, number of web pages, number of duplicates, number of binary documents by type, number of images, average number of images per web page, …).
Visualize links and include images into the report. If the network is too big, take only a portion of it or high-level representation (e.g. interconnectedness of specific domains). Use visualization libraries such as [D3js](https://d3js.org/), [visjs](http://visjs.org/), [sigmajs](http://sigmajs.org/) or [gephi](https://gephi.org/).
Describe how you implemented deduplication strategy with partial matching.



## 5. Submission instructions

Only one of the group members should make a submission of the assignment in Moodle. The Moodle submission should contain only one .txt file with a link to the private GitHub repository (which you will use for all the submissions during the course) that contains the following:

- a file pa1/db
- a file pa1/report.pdf - PDF report.
- a file pa1/README.md - Short description of the project and instructions to install, set up and run the crawler. Also include instructions to import the database to pgadmin. 
- a folder pa1/crawler - Implementation of the crawler.

NOTE: The database dump you submit should not contain images or binary data. Filename db should be of Custom export format that you can export directly using pgAdmin:

