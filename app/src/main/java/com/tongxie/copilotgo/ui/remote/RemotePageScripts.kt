package com.tongxie.copilotgo.ui.remote

internal object RemotePageScripts {
    fun enhancements(immersive: Boolean): String = """
        (function(){
          function isCopilot(){
            return location.origin === 'https://github.com' &&
              (location.pathname === '/copilot' || location.pathname.indexOf('/copilot/') === 0);
          }
          var ID='cg-immersive-style';
          var CSS='.AppHeader,header.AppHeader,.js-header-wrapper,.footer,footer.footer{display:none!important;} body{padding-top:0!important;}';
          window.__cgImmersiveEnabled=$immersive;
          function ensure(){
            var ex=document.getElementById(ID);
            if(window.__cgImmersiveEnabled && isCopilot()){
              if(!ex){
                var s=document.createElement('style');
                s.id=ID;
                s.textContent=CSS;
                (document.head||document.documentElement).appendChild(s);
              }
            } else if(ex) {
              ex.remove();
            }
          }
          ensure();
          if(!isCopilot()) return;
          if(!window.__cgImmersiveObserver && window.MutationObserver){
            window.__cgImmersiveObserver=new MutationObserver(ensure);
            window.__cgImmersiveObserver.observe(document.documentElement,{childList:true,subtree:true});
          }
          if(window.__cgEnterNewline) return;
          window.__cgEnterNewline=true;
          window.addEventListener('keydown',function(e){
            if(!isCopilot() || (e.key !== 'Enter' && e.keyCode !== 13)) return;
            if(e.shiftKey || e.ctrlKey || e.metaKey || e.altKey || e.isComposing || e.keyCode === 229) return;
            var el=e.target;
            if(!el || el.readOnly || el.disabled) return;
            var tag=(el.tagName||'').toLowerCase();
            if(tag !== 'textarea' && !el.isContentEditable) return;
            e.preventDefault();
            e.stopImmediatePropagation();
            if(tag === 'textarea'){
              var start=el.selectionStart, end=el.selectionEnd;
              el.setRangeText('\n',start,end,'end');
              el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertLineBreak',data:'\n'}));
            } else {
              document.execCommand('insertLineBreak');
            }
          },true);
        })();
    """.trimIndent()
}
