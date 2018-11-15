<html>
  <head>
    <title>$page.title$</title>
    <link rel="stylesheet" type="text/css" href="$page.base$css/docs.css"/>
    <link href="https://fonts.googleapis.com/css?family=Share+Tech+Mono|Source+Sans+Pro:400,400i,700,700i" rel="stylesheet"/>
  </head>
  <body>
    <div id="navigationColumn">
      <div id="navTitle">
      	 <div id="navHomeLink" $if(page.home.active)$class="active"$endif$>$page.home.html$</div>
      	 <div id="navProjectVersion">v.$page.properties.("project.version.short")$</div>
	 <div class="retroRule">-=-=-=-=-=-</div>
      </div>	
      <div id="navigation">$page.navigation$</div>
    </div>
    <div id="page">
      <div id="pageContent">$page.content$</div>
      <div id="pageFooter">
       <b>&copy; 2018 Machinery For Change, Inc</b><br/>
       <i>This software provided <b>without warranty</b> under your choice of the <a href="http://www.gnu.org/licenses/lgpl-2.1.html">Lesser GNU Public License, v.2.1 (LGPL)</a> or
       the <a href="http://www.eclipse.org/legal/epl-v10.html">Eclipse Public License, v.1.0 (EPL)]</a>
      </div>
    </div>
    <div id="rightColumn">
      Hi!
    </div>
  </body>
</html>