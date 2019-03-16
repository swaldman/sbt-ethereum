<html>
  <head>
    <title>$page.title$</title>
    <link rel="stylesheet" type="text/css" href="$page.base$css/docs.css"/>
    <link rel="stylesheet" type="text/css" href="$page.base$css/fonts.css"/>
    <script src="$page.base$js/docs.js"></script>
  </head>
  <body onload="init()">
    <div id="navigationColumn">
      <div id="navTitle">
      	 <div id="navHomeLink" $if(page.home.active)$class="active"$endif$>$page.home.html$</div>
      	 <div id="navProjectVersion">v.$page.properties.("project.version")$</div>
	 <div class="retroRule">-=-=-=-=-=-</div>
      </div>	
      <div id="navigation">$page.navigation$</div>
      <div id="navFooter">
	<div class="retroRule">+ + + + +</div>
	<div id="pageToc">$page.toc$</div>
      </div>
    </div>
    <div id="page">
      <div id="pageContent">$page.content$</div>
    </div>
    <div id="rightColumn">
    </div>
    <div id="pageFooter">
      <b>&copy; 2019 Machinery For Change LLC</b> &mdash;
      <i>This software provided <b>without warranty to the extent permitted by applicable law</b> under your choice of the <a href="http://www.gnu.org/licenses/lgpl-2.1.html">Lesser GNU Public License, v.2.1 (LGPL)</a> or
	the <a href="http://www.eclipse.org/legal/epl-v10.html">Eclipse Public License, v.1.0 (EPL)]</a></i>
    </div>
  </body>
</html>
