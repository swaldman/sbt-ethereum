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
    if (target.style.display === "none") {
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


/* Detailed tab completion tutorial? <a id="uasc_optional_1_triangle" class="optional-triangle" href="javascript:toggleVisibilityWithTriangle('uasc_optional_1', 'uasc_optional_1_triangle')">&#x25B6;</a> */

