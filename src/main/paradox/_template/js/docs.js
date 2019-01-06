function toggleVisibility(id) {
    var target = document.getElementById(id);
    if (target.style.display === "none") {
	target.style.display = "block";
    } else {
	target.style.display = "none";
    }
}
function toggleVisibilityWithTriangle(idTarget, idTriangle) {
    var target = document.getElementById(idTarget);
    var triangle = document.getElementById(idTriangle);
    if (target.style.display === "none") {
	target.style.display = "block";
	triangle.text = "\u25BC"
    } else {
	target.style.display = "none";
	triangle.text = "\u25B6"
    }
}
function toggleVisibilityWithReplaceControl(idTarget, idReplaceControl, openControlContents, closeControlContents) {
    var target = document.getElementById(idTarget);
    var replaceControl = document.getElementById(idReplaceControl);
    var replaceControlTriangle = document.getElementById(idReplaceControl + "-triangle");
    if (target.style.display === "none" || !(target.style.display)) { // at the first click , target.style.display is undefined despite its existence in the css
	target.style.display = "block";
	replaceControl.text = closeControlContents
	replaceControlTriangle.text = "\u25BC";
    } else {
	target.style.display = "none";
	replaceControl.text = openControlContents;
	replaceControlTriangle.text = "\u25B6";
    }
}
function writeOptionalReplaceControl(idTarget, idReplaceControl, openControlContents, closeControlContents) {
    var onClickAction = "toggleVisibilityWithReplaceControl('" + idTarget + "', '" + idReplaceControl + "', '" + openControlContents + "', '" + closeControlContents +"')"
    document.write( "<div class=\042optional-replace-control-parent\042>")
    document.write( "    <a id=\042" + idReplaceControl + "-triangle\042 class=\042optional-replace-control-triangle\042 onClick=\042" + onClickAction + "\042>\u25B6</a>\n")
    document.write( "    <a id=\042" + idReplaceControl + "\042 class=\042optional-replace-control\042 onClick=\042" + onClickAction + "\042>" + openControlContents + "</a>\n" )
    document.write( "</div>" )
}
function displayInnerOnlyTocListElements() {
    const topElems = document.querySelectorAll(".inner-only > .toc > ul > li");
    if ( topElems !== null ) {
	topElems.forEach( elem => {
	    var uls = elem.querySelector("ul");
	    if ( uls === null || uls.length == 0 ) {
		elem.style.display = "none";
	    }
	    else {
		elem.style.display = "block";
		var pageLinks = elem.querySelector( "a.page" )
		if ( pageLinks !== null ) {
		    pageLinks.style.display = "none";
		}
	    }
	});
    }    
}
function findTocSecondLevelList( tocParentId, index ) {
    // console.log( "findTocSecondLevelList( " + tocParentId + ", " + index + " )" )
    const tocParent = document.getElementById( tocParentId );
    // console.log( tocParent );
    const topElems = Array.from( tocParent.querySelectorAll(".toc > ul > li") );
    // console.log( topElems );
    if ( topElems !== null ) {
	const chargedLiElems = topElems.filter( elem => {
	    const uls = elem.querySelectorAll("ul");
	    return ( uls !== null && uls.length > 0 );
	} );
	// console.log( chargedLiElems );
	// console.log( chargedLiElems[index] );
	// console.log( chargedLiElems[index].querySelectorAll( "ul" ) );
	if ( index < chargedLiElems.length ) {
	    const out = chargedLiElems[index].querySelectorAll( "ul" );
	    // console.log( out );
	    if ( out.length != 1 ) {
		console.log("Unexpected number of second-level lists from 'findTocSecondLevelList( tocParentId, index )': " + out.length )
	    }
	    return out[0];
	}
	else {
	    console.log("index >= number-of-second-level-lists, index: " + index + ", number-of-second-level-lists: " + chargedLiElems.length)
	    return null;
	}
    }
    else {
	// console.log("Did not find any elements in top-level list.")
	return null;
    }
}
function copyTocSecondLevelList( tocParentId, index, targetDivId ) {
    const targetDiv = document.getElementById( targetDivId );
    if ( targetDiv !== null ) {
	const secondLevelList = findTocSecondLevelList( tocParentId, index );
	if ( secondLevelList !== null ) {
	    targetDiv.innerHTML = "<ul>" + secondLevelList.innerHTML + "</ul>";
	}
    }
}
function showNavigationParentListOfActive() {
    const active = document.getElementById( "navigation" ).querySelectorAll( "ul a.page.active" );
    if ( active !== null && active.length > 0) { // should always be 0 or 1
	var list = Array.from( active )[0];
	while ( list !== null ) {
	    const nname = list.nodeName.toUpperCase();
	    // console.log( nname );
	    if ( nname === "UL" ) {
		list.style.display = "block";
	    }
	    else if ( nname === "DIV" ) {
		break;
	    }
	    list = list.parentElement;
	}
    }
}

function init() {
    copyTocSecondLevelList( "ethAddressToc", 0, "addressAliasList" );
    copyTocSecondLevelList( "ethAddressToc", 1, "senderList" );
    
    copyTocSecondLevelList( "ethContractToc", 0, "abiList" );
    copyTocSecondLevelList( "ethContractToc", 1, "compilationList" );
    
    copyTocSecondLevelList( "ethLanguageToc", 0, "solidityList" );

    copyTocSecondLevelList( "ethNodeToc", 0, "chainIdList" );
    copyTocSecondLevelList( "ethNodeToc", 1, "urlList" );
    
    copyTocSecondLevelList( "ethTransactionToc", 0, "gasList" );
    copyTocSecondLevelList( "ethTransactionToc", 1, "nonceList" );

    showNavigationParentListOfActive()
}

/* Detailed tab completion tutorial? <a id="uasc_optional_1_triangle" class="optional-triangle" href="javascript:toggleVisibilityWithTriangle('uasc_optional_1', 'uasc_optional_1_triangle')">&#x25B6;</a> */

